package com.platform.gateway.filter;

import com.platform.gateway.security.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebHandler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAT 스코프 필터 단독 검증. 다운스트림은 "도달했다"만 기록하는 WebHandler로 대체한다 —
 * 여기서 보는 것은 "무엇이 다운스트림에 닿고 무엇이 403으로 잘리는가" 하나뿐이다.
 *
 * <p>토큰은 실제 JWT 형태로 서명해 만든다(서명 검증은 이 필터의 몫이 아니지만, 파싱은 진짜여야 한다).
 */
class PatScopeWebFilterTest {

    private final AtomicBoolean downstreamCalled = new AtomicBoolean(false);

    private WebTestClient client() {
        WebHandler downstream = exchange -> {
            downstreamCalled.set(true);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        return WebTestClient.bindToWebHandler(downstream)
                .webFilter(new PatScopeWebFilter())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static String jwt(String provider, List<String> scopes) {
        return TestJwt.of("member-1", provider, scopes);
    }

    private static String pat(String... scopes) {
        return jwt("PAT", List.of(scopes));
    }

    private void allowed(String method, String path, String token) {
        downstreamCalled.set(false);
        client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(java.net.URI.create("http://localhost" + path)) // 템플릿 인코딩 없이 원문 그대로(%·; 변형 시험)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
        assertThat(downstreamCalled).as(method + " " + path + "는 다운스트림에 닿아야 한다").isTrue();
    }

    private void denied(String method, String path, String token) {
        downstreamCalled.set(false);
        client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(java.net.URI.create("http://localhost" + path)) // 템플릿 인코딩 없이 원문 그대로(%·; 변형 시험)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody().jsonPath("$.error").isEqualTo("insufficient_scope");
        assertThat(downstreamCalled).as(method + " " + path + "는 잘려야 한다").isFalse();
    }

    // --- 스코프 강제 -----------------------------------------------------------

    @Test
    void 읽기_스코프로_읽기는_되고_쓰기는_403이다() {
        allowed("GET", "/api/wiki/pages/7", pat("wiki:read"));
        denied("POST", "/api/wiki/pages", pat("wiki:read"));
    }

    @Test
    void 쓰기_스코프는_읽기도_포함한다() {
        allowed("POST", "/api/wiki/pages", pat("wiki:write"));
        allowed("GET", "/api/wiki/pages/7", pat("wiki:write"));
    }

    @Test
    void 다른_제품_스코프로는_넘어갈_수_없다() {
        denied("GET", "/api/alm/issues", pat("wiki:write"));
        allowed("GET", "/api/alm/issues", pat("alm:read"));
    }

    @Test
    void 관리자_API는_admin_스코프를_추가로_요구한다() {
        denied("GET", "/api/wiki/admin/stats", pat("wiki:read"));
        allowed("GET", "/api/wiki/admin/stats", pat("wiki:read", "admin"));
        denied("POST", "/api/migration/jobs", pat("wiki:write", "alm:write", "org:write"));
        allowed("POST", "/api/migration/jobs", pat("admin"));
    }

    @Test
    void 게시판은_읽기_쓰기_스코프로_갈린다() {
        denied("GET", "/api/board/posts", pat("wiki:write", "alm:write", "org:write", "admin"));
        allowed("GET", "/api/board/posts", pat("board:read"));
        denied("POST", "/api/board/posts", pat("board:read"));
        allowed("POST", "/api/board/posts", pat("board:write"));
    }

    /** 검색 질의는 POST(GraphQL)뿐이라 search:read가 그대로 통해야 한다. */
    @Test
    void 검색은_POST여도_search_read로_통과한다() {
        denied("POST", "/api/search/graphql", pat("wiki:read", "board:read"));
        allowed("POST", "/api/search/graphql", pat("search:read"));
        denied("POST", "/api/search/admin/reindex", pat("search:read"));
        allowed("POST", "/api/search/admin/reindex", pat("search:read", "admin"));
    }

    @Test
    void 표에_없는_접두사는_스코프를_다_가져도_거부다() {
        denied("POST", "/api/auth/tokens", pat("admin"));
        denied("GET", "/api/boards/posts", pat("board:write", "admin"));
    }

    @Test
    void 관리_대시보드_API는_admin_스코프_PAT도_거부한다() {
        denied("GET", "/api/platform/health", pat("admin"));
        denied("GET", "/api/platform/stats/tokens", pat("wiki:read", "alm:read", "org:read", "admin"));
    }

    @Test
    void 내_프로필은_어떤_스코프로도_허용되지만_하위_경로는_아니다() {
        allowed("GET", "/api/me", pat("wiki:read"));
        denied("DELETE", "/api/me/tokens", pat("wiki:write", "admin"));
    }

    // --- 손대지 않는 것 ---------------------------------------------------------

    /** 브라우저 세션 JWT에는 provider 클레임이 없다 — 사람이 쓰는 요청은 이 필터를 통과해야 한다. */
    @Test
    void 세션_JWT는_스코프가_없어도_통과한다() {
        String session = jwt(null, null);
        allowed("POST", "/api/board/posts", session);
        allowed("GET", "/api/platform/health", session);
        allowed("POST", "/api/wiki/admin/stats", session);
    }

    /** provider가 PAT가 아니면(예: 에이전트 서비스 토큰) 이 필터의 소관이 아니다. */
    @Test
    void provider가_PAT가_아닌_토큰은_통과한다() {
        allowed("POST", "/api/board/posts", jwt("AGENT", List.of("wiki:read")));
    }

    /**
     * 스코프 클레임이 없는 PAT JWT — 열어주지 않는다(P-21). auth-server는 모든 교환 JWT에 {@code scope}를
     * 싣고(V5/V6 백필로 기존 행도 NOT NULL) 교환 JWT 수명은 300초라, 클레임 없는 PAT JWT는 정상 경로로는
     * 존재할 수 없다. 클레임 없음 = 빈 스코프 = {@code /api/me} 외 거부.
     */
    @Test
    void 스코프_클레임이_없는_PAT는_빈_스코프로_취급해_거부한다() {
        denied("POST", "/api/wiki/pages", jwt("PAT", null));
        denied("GET", "/api/wiki/pages/7", jwt("PAT", null));
        allowed("GET", "/api/me", jwt("PAT", null));
    }

    /** 관리 작업은 org:write로 못 한다 — 권한 부여·구성원 상태·메일 설정·초대는 admin 스코프(P-03). */
    @Test
    void org_관리_작업은_org_write만으로는_403이다() {
        denied("POST", "/api/org/grants", pat("org:write"));
        denied("PATCH", "/api/org/members/42", pat("org:write"));
        denied("PUT", "/api/org/settings/mail", pat("org:write"));
        denied("POST", "/api/org/invitations", pat("org:write"));
        denied("POST", "/api/org/teams", pat("org:write"));
        allowed("POST", "/api/org/grants", pat("org:write", "admin"));
        allowed("PATCH", "/api/org/members/42", pat("org:write", "admin"));
        // 관리 아닌 org 작업은 그대로
        allowed("GET", "/api/org/members", pat("org:read"));
        allowed("PUT", "/api/org/me/avatar", pat("org:write"));
    }

    /** 경로 정규화 우회 — 다운스트림(Spring MVC)이 같은 핸들러로 보내는 변형도 admin을 요구한다. */
    @Test
    void 경로_변형으로_admin_요구를_우회할_수_없다() {
        denied("POST", "/api/org//grants", pat("org:write"));
        denied("POST", "/api/org/grants;x=1", pat("org:write"));
        denied("POST", "/api/org/%67rants", pat("org:write"));
        denied("POST", "/api/org/x/../grants", pat("org:write"));
    }

    /** 다만 금지 경로는 구버전 토큰에도 열지 않는다 — 열면 규칙이 무의미해진다. */
    @Test
    void 구버전_PAT도_플랫폼_API에는_못_들어온다() {
        denied("GET", "/api/platform/health", jwt("PAT", null));
    }

    @Test
    void 스코프가_빈_배열이면_조건_있는_경로는_전부_403이다() {
        denied("GET", "/api/wiki/pages/7", pat());
        allowed("GET", "/api/me", pat());
    }

    @Test
    void 무토큰과_JWT가_아닌_Bearer는_통과한다() {
        downstreamCalled.set(false);
        client().get().uri("/api/board/posts").exchange().expectStatus().isOk();
        assertThat(downstreamCalled).isTrue();

        // PAT 원문(교환 전)·에이전트 PAT는 JWT가 아니라 파싱조차 되지 않는다 — 교환 필터의 몫이다.
        allowedRaw("GET", "/api/wiki/pages", "Bearer chanho_pat_" + "A".repeat(43));
        allowedRaw("POST", "/api/agent/mcp/tools/call", "Bearer agp_" + "B".repeat(40));
    }

    private void allowedRaw(String method, String path, String authorization) {
        downstreamCalled.set(false);
        client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(java.net.URI.create("http://localhost" + path)) // 템플릿 인코딩 없이 원문 그대로(%·; 변형 시험)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange()
                .expectStatus().isOk();
        assertThat(downstreamCalled).isTrue();
    }
}
