package com.platform.gateway.filter;

import com.github.benmanes.caffeine.cache.Ticker;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebHandler;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAT 교환 필터 단독 검증. auth-server는 MockWebServer 스텁, 다운스트림은 Authorization 헤더를
 * 그대로 기록하는 WebHandler로 대체한다 — 실제 JWKS/보안체인 없이 "무엇이 다운스트림에 도달하는가"만 본다.
 */
class PatExchangeWebFilterTest {

    private static final String PAT = "chanho_pat_" + "A".repeat(43);
    private static final String JWT = "header.payload.signature";
    /** agent-service 자체 PAT — 접두사가 다르므로 이 필터의 소관이 아니다. */
    private static final String AGENT_PAT = "agp_" + "B".repeat(40);

    private MockWebServer authServer;
    /** 캐시 만료를 실시간 대기 없이 앞당기기 위한 가짜 시계(나노초). */
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final AtomicReference<String> downstreamAuthHeader = new AtomicReference<>();
    private final AtomicReference<Boolean> downstreamCalled = new AtomicReference<>(false);

    @BeforeEach
    void startAuthServer() throws Exception {
        authServer = new MockWebServer();
        authServer.start();
    }

    @AfterEach
    void stopAuthServer() throws Exception {
        authServer.shutdown();
    }

    // --- 도우미 -------------------------------------------------------------

    private WebTestClient clientWithSecret(String secret) {
        return clientWith(secret, Duration.ofSeconds(15));
    }

    /** 타임아웃은 테스트마다 정한다 — 빌드 머신이 바쁠 때 프로덕션 2s가 정상 경로를 503으로 뒤집는다. */
    private WebTestClient clientWith(String secret, Duration exchangeTimeout) {
        PatExchangeClient exchangeClient = new PatExchangeClient(
                WebClient.builder(), authServer.url("/").toString(), secret, exchangeTimeout);
        WebHandler downstream = exchange -> {
            downstreamCalled.set(true);
            downstreamAuthHeader.set(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        return WebTestClient.bindToWebHandler(downstream)
                .webFilter(new PatExchangeWebFilter(exchangeClient, ticker))
                .configureClient()
                .responseTimeout(Duration.ofSeconds(20))
                .build();
    }

    private WebTestClient client() {
        return clientWithSecret("test-internal-secret");
    }

    private static MockResponse exchangeOk(String jwt, long expiresInSeconds) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"accessToken\":\"" + jwt + "\",\"expiresInSeconds\":" + expiresInSeconds + "}");
    }

    private static MockResponse errorResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    // --- 성공 경로 -----------------------------------------------------------

    @Test
    void patIsExchangedAndDownstreamSeesPlatformJwt() throws Exception {
        authServer.enqueue(exchangeOk(JWT, 300));

        client().get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer " + JWT);

        RecordedRequest recorded = authServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/internal/pat/exchange");
        assertThat(recorded.getHeader("X-Internal-Secret")).isEqualTo("test-internal-secret");
        assertThat(recorded.getBody().readUtf8()).contains(PAT);
    }

    @Test
    void bearerSchemeIsCaseInsensitive() {
        authServer.enqueue(exchangeOk(JWT, 300));

        client().get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "bEaReR " + PAT)
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer " + JWT);
    }

    @Test
    void secondCallWithinCacheTtlDoesNotHitAuthServer() {
        authServer.enqueue(exchangeOk(JWT, 300));
        WebTestClient client = client();

        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isOk();
        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer " + JWT);
        assertThat(authServer.getRequestCount()).isEqualTo(1); // 두 번째는 캐시 히트
    }

    /**
     * 캐시 항목의 TTL(60s)이 남아 있어도 JWT 자체가 만료 30초 안이면 재사용하지 않는다.
     * auth-server가 20초짜리 JWT를 주면 첫 응답부터 이미 가드 안이라 두 번째 요청은 다시 교환한다.
     */
    @Test
    void jwtCloseToExpiryIsNotReusedFromCache() {
        authServer.enqueue(exchangeOk(JWT, 20));
        authServer.enqueue(exchangeOk("second." + JWT, 300));
        WebTestClient client = client();

        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isOk();
        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isOk();

        assertThat(authServer.getRequestCount()).isEqualTo(2);
        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer second." + JWT);
    }

    // --- 실패 경로 -----------------------------------------------------------

    @Test
    void invalidPatReturns401AndIsNegativelyCached() {
        authServer.enqueue(errorResponse(401, "{\"error\":\"invalid_token\"}"));
        WebTestClient client = client();

        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody().jsonPath("$.error").isEqualTo("invalid_token");

        // 부정 캐시 10s — 두 번째 요청은 auth-server를 때리지 않는다(무차별 대입 완화).
        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("invalid_token");

        assertThat(authServer.getRequestCount()).isEqualTo(1);
        assertThat(downstreamCalled.get()).isFalse();
    }

    /**
     * 403 비밀 불일치처럼 영구적 설정 오류면 모든 PAT 요청이 2초 타임아웃까지 auth-server를 때린다.
     * 아주 짧은(2s) 불능 캐시가 그 폭주를 막는다 — 그 사이 재요청은 auth-server를 부르지 않는다.
     */
    @Test
    void authServerErrorReturns503AndIsCachedBriefly() {
        authServer.enqueue(errorResponse(500, "boom"));
        WebTestClient client = client();

        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("auth_unavailable");

        nanos.addAndGet(Duration.ofMillis(1900).toNanos()); // 아직 2초 안
        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("auth_unavailable");

        assertThat(authServer.getRequestCount()).isEqualTo(1);
        assertThat(downstreamCalled.get()).isFalse();
    }

    /**
     * 불능 캐시가 정상 토큰을 오래 가두면 안 된다 — 2초가 지나면 곧바로 재시도하고,
     * auth-server가 회복돼 있으면 그 요청부터 통과한다.
     */
    @Test
    void unavailableEntryExpiresAfterTwoSecondsAndRetriesAuthServer() {
        authServer.enqueue(errorResponse(500, "boom"));
        authServer.enqueue(exchangeOk(JWT, 300)); // 회복
        WebTestClient client = client();

        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isEqualTo(503);

        nanos.addAndGet(Duration.ofMillis(2100).toNanos()); // 2초 경과
        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isOk();

        assertThat(authServer.getRequestCount()).isEqualTo(2);
        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer " + JWT);
    }

    /** 무효(401) 항목은 10초짜리다 — 2초로 짧아진 불능 TTL과 섞이지 않았는지 확인한다. */
    @Test
    void invalidEntryOutlivesTheUnavailableTtl() {
        authServer.enqueue(errorResponse(401, "{\"error\":\"invalid_token\"}"));
        WebTestClient client = client();

        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isUnauthorized();

        nanos.addAndGet(Duration.ofSeconds(5).toNanos()); // 불능 TTL(2s)은 지났지만 무효 TTL(10s)은 남았다
        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isUnauthorized();

        assertThat(authServer.getRequestCount()).isEqualTo(1);
    }

    /** 비밀 불일치(403)는 배포 설정 오류다 — 사용자 토큰을 무효라고 단정하지 않고 불능으로 올린다. */
    @Test
    void secretMismatchReturns503NotInvalidToken() {
        authServer.enqueue(errorResponse(403, "{\"error\":\"forbidden\"}"));

        client().get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("auth_unavailable");
    }

    @Test
    void authServerTimeoutReturns503() {
        // 교환 타임아웃 500ms < 응답 지연 2s — 프로덕션은 2s지만 여기서는 비율만 재현하면 된다.
        authServer.enqueue(exchangeOk(JWT, 300).setBodyDelay(2, TimeUnit.SECONDS));

        clientWith("test-internal-secret", Duration.ofMillis(500)).get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("auth_unavailable");

        assertThat(downstreamCalled.get()).isFalse();
    }

    @Test
    void emptyInternalSecretRejectsPatWithoutCallingAuthServer() {
        clientWithSecret("").get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("invalid_token");

        assertThat(authServer.getRequestCount()).isZero(); // fail-closed — 교환 시도조차 하지 않는다
        assertThat(downstreamCalled.get()).isFalse();
    }

    // --- 통과 경로(건드리지 않는다) --------------------------------------------

    @Test
    void plainJwtBearerPassesThroughUntouched() {
        client().get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT)
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer " + JWT);
        assertThat(authServer.getRequestCount()).isZero();
    }

    @Test
    void requestWithoutAuthorizationHeaderPassesThroughUntouched() {
        client().get().uri("/api/board/posts")
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamCalled.get()).isTrue();
        assertThat(downstreamAuthHeader.get()).isNull();
        assertThat(authServer.getRequestCount()).isZero();
    }

    /**
     * agent-service는 `/api/agent/mcp/**` 로 자기 PAT(`agp_…`)를 받아 스스로 검증한다.
     * 게이트웨이가 이 토큰을 교환 대상으로 착각해 헤더를 갈아끼우면 agent-service의 인증이 통째로 깨진다.
     * 우리 소관은 `chanho_pat_` 접두사 하나뿐이다 — 나머지 Bearer는 원문 그대로 흘려보낸다.
     */
    @Test
    void agentServicePatWithDifferentPrefixPassesThroughUntouched() {
        client().post().uri("/api/agent/mcp/tools/call")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + AGENT_PAT)
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer " + AGENT_PAT);
        assertThat(authServer.getRequestCount()).isZero();
    }

    @Test
    void nonBearerSchemeIsNotTreatedAsPat() {
        client().get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + PAT)
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Basic " + PAT);
        assertThat(authServer.getRequestCount()).isZero();
    }

    // --- 접두사 판별 순수 함수 --------------------------------------------------

    @Test
    void extractPatTokenRecognizesOnlyPatPrefixedBearerTokens() {
        assertThat(PatExchangeWebFilter.extractPatToken("Bearer " + PAT)).isEqualTo(PAT);
        assertThat(PatExchangeWebFilter.extractPatToken("BEARER " + PAT)).isEqualTo(PAT);
        assertThat(PatExchangeWebFilter.extractPatToken("Bearer " + JWT)).isNull();
        assertThat(PatExchangeWebFilter.extractPatToken("Bearer " + AGENT_PAT)).isNull(); // agent-service PAT
        assertThat(PatExchangeWebFilter.extractPatToken("Bearer chanho_pat")).isNull();   // 접두사 미완성
        assertThat(PatExchangeWebFilter.extractPatToken("Bearer")).isNull();
        assertThat(PatExchangeWebFilter.extractPatToken("")).isNull();
        assertThat(PatExchangeWebFilter.extractPatToken(null)).isNull();
        assertThat(PatExchangeWebFilter.extractPatToken(PAT)).isNull(); // 스킴 없음
    }

}
