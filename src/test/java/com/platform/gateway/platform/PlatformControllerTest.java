package com.platform.gateway.platform;

import com.platform.gateway.platform.HealthReport.ComponentHealth;
import com.platform.gateway.security.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 API의 응답 계약. 판정 세 갈래(통과·거부·불능)가 각각 다른 상태코드·오류 코드로 나가는지만 본다 —
 * 판정 자체는 {@link AdminGateTest}, 집계 자체는 {@link HealthAggregatorTest}가 검증한다.
 */
class PlatformControllerTest {

    private static final String SESSION = "Bearer " + TestJwt.session("member-42");

    private final AdminGate adminGate = mock(AdminGate.class);
    private final HealthAggregator healthAggregator = mock(HealthAggregator.class);
    private final AuthStatsClient authStatsClient = mock(AuthStatsClient.class);

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(
                        new PlatformController(adminGate, healthAggregator, authStatsClient))
                .build();
        when(healthAggregator.report()).thenReturn(Mono.just(new HealthReport(Instant.now(), 20,
                List.of(new ComponentHealth("gateway-server", "게이트웨이", "service", "UP", 0L, "1.0.0", null)))));
        when(authStatsClient.stats()).thenReturn(Mono.just(Map.of(
                "activeTokens", 3, "usersWithTokens", 2, "expiringWithin7Days", 1)));
    }

    private void decision(AdminGate.Decision decision) {
        when(adminGate.check(anyString())).thenReturn(Mono.just(decision));
    }

    @Test
    void 관리자는_헬스_집계를_받는다() {
        decision(AdminGate.Decision.ALLOWED);

        client.get().uri("/api/platform/health")
                .header(HttpHeaders.AUTHORIZATION, SESSION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.cacheTtlSeconds").isEqualTo(20)
                // 프론트가 Date로 파싱한다 — 숫자 타임스탬프로 바뀌면 화면의 "마지막 점검"이 깨진다.
                .jsonPath("$.checkedAt").value(org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T.*Z"))
                .jsonPath("$.components[0].id").isEqualTo("gateway-server")
                .jsonPath("$.components[0].status").isEqualTo("UP")
                .jsonPath("$.components[0].version").isEqualTo("1.0.0");
    }

    @Test
    void 관리자가_아니면_403_forbidden이다() {
        decision(AdminGate.Decision.DENIED);

        client.get().uri("/api/platform/health")
                .header(HttpHeaders.AUTHORIZATION, SESSION)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("forbidden");
    }

    /** org-service 장애를 "권한 없음"으로 바꾸지 않는다 — 화면이 엉뚱한 안내를 하게 된다. */
    @Test
    void org가_불능이면_503_org_unavailable이다() {
        decision(AdminGate.Decision.UNAVAILABLE);

        client.get().uri("/api/platform/health")
                .header(HttpHeaders.AUTHORIZATION, SESSION)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("org_unavailable");
    }

    /** 관리 화면 전용 — PAT는 admin 스코프가 있어도 org에 물어보기 전에 잘린다. */
    @Test
    void PAT는_admin_스코프가_있어도_거부된다() {
        client.get().uri("/api/platform/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.pat("member-42", List.of("admin")))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("forbidden");

        verify(adminGate, never()).check(anyString());
    }

    @Test
    void 토큰_통계는_auth_server_응답을_그대로_돌려준다() {
        decision(AdminGate.Decision.ALLOWED);

        client.get().uri("/api/platform/stats/tokens")
                .header(HttpHeaders.AUTHORIZATION, SESSION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.activeTokens").isEqualTo(3)
                .jsonPath("$.usersWithTokens").isEqualTo(2)
                .jsonPath("$.expiringWithin7Days").isEqualTo(1);
    }

    @Test
    void auth_server가_불능이면_503_auth_unavailable이다() {
        decision(AdminGate.Decision.ALLOWED);
        when(authStatsClient.stats()).thenReturn(Mono.empty());

        client.get().uri("/api/platform/stats/tokens")
                .header(HttpHeaders.AUTHORIZATION, SESSION)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("auth_unavailable");
    }
}
