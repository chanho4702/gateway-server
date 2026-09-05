package com.platform.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게이트웨이 JWT 조기차단. 보호 경로는 다운스트림 도달 전에 401이어야 하고(다운스트림 없이 테스트 가능),
 * 공개 경로는 보안을 통과해 프록시까지 가야 한다(다운스트림 없으니 401만 아니면 됨).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigTest {

    @LocalServerPort
    int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    void protectedPathWithoutTokenIs401() {
        client().post().uri("/api/board/posts").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/me").exchange().expectStatus().isUnauthorized();
    }

    /**
     * agent-service MCP 엔드포인트는 PAT(agp_*, JWT 아님)로 자체 인증한다 — 게이트웨이 JWT 필터가
     * 여기를 막으면 정상 PAT 요청도 401로 조기 차단된다. 인증 실패 판정은 다운스트림(agent-service)
     * SecurityConfig의 몫이라 여기서는 401이 아니면 통과.
     */
    @Test
    void agentMcpPathPassesSecurityWithoutJwt() {
        client().post().uri("/api/agent/mcp/anything").exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
    }

    /**
     * permitAll은 인가만 건너뛴다 — oauth2ResourceServer가 같은 체인에 있으면
     * BearerTokenAuthenticationFilter가 authorizeExchange보다 먼저 실행되어, PAT(agp_*, JWT 아님)를
     * JWT로 디코드 시도하다 실패해 인가 단계에 닿기도 전에 401을 낸다(agent-service T7과 동일 함정,
     * 실측: task-14 E2E). 그래서 /api/agent/mcp/**는 oauth2ResourceServer가 아예 없는 별도
     * SecurityWebFilterChain(순서 1)으로 분리해야 한다.
     */
    @Test
    void agentMcpPathWithPatBearerBypassesJwtDecode() {
        client().post().uri("/api/agent/mcp/anything")
                .header("Authorization", "Bearer agp_dummyPatValueThatIsNotAJwt")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
    }

    @Test
    void agentMcpPathOptionsPreflightStillWorks() {
        client().options().uri("/api/agent/mcp/anything")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401))
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    }

    /**
     * /api/agent/mcp/** 이외의 agent 경로(tokens/personas 등)는 permitAll에 들어가지 않는다 —
     * anyExchange().authenticated()에 기대는 계약이라, 누가 permitAll을 넓히면 여기서 깨진다.
     */
    @Test
    void agentNonMcpPathsWithoutTokenAre401() {
        client().get().uri("/api/agent/tokens").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/agent/personas").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void garbageBearerTokenIs401() {
        client().get().uri("/api/me")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange().expectStatus().isUnauthorized();
    }

    /**
     * 검색 경로(Wave C)는 전량 인증 필수. GraphQL은 단일 URL이라 "이 필드만 공개" 같은
     * 경로 기반 예외를 둘 수 없고, 재색인은 관리자 조작이라 더 강하다.
     * anyExchange().authenticated()에 기대는 계약이라, 누가 permitAll을 넓히면 여기서 깨진다.
     */
    @Test
    void searchPathsWithoutTokenAre401() {
        client().post().uri("/api/search/graphql").exchange().expectStatus().isUnauthorized();
        client().post().uri("/api/search/admin/reindex").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/search/admin/reindex/some-job-id").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void collaborationUpgradePathUsesTicketAuthButWikiRestStillRequiresJwt() {
        client().get().uri("/api/wiki/collaboration").exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
        client().post().uri("/api/wiki/collaboration/pages/7/bootstrap").exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
        client().post().uri("/api/wiki/pages/7/collaboration-ticket").exchange()
                .expectStatus().isUnauthorized();
        client().get().uri("/api/wiki/pages/7").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void unauthorizedResponseCarriesCorsHeaders() {
        // 401이라도 CORS 헤더가 있어야 브라우저 fetch가 상태를 읽고 refresh 흐름을 탄다.
        client().post().uri("/api/board/posts")
                .header("Origin", "http://localhost:5173")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    }

    @Test
    void publicPathsPassSecurity() {
        // 다운스트림이 없어 5xx/fallback이 나는 건 정상 — 보안이 401로 막지만 않으면 된다.
        client().get().uri("/api/board/posts").exchange()
                .expectStatus().value(s -> assertThat(s).isNotEqualTo(401));
        client().post().uri("/api/auth/refresh").exchange()
                .expectStatus().value(s -> assertThat(s).isNotEqualTo(401));
        client().get().uri("/.well-known/jwks.json").exchange()
                .expectStatus().value(s -> assertThat(s).isNotEqualTo(401));
    }
}
