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

    @Test
    void garbageBearerTokenIs401() {
        client().get().uri("/api/me")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange().expectStatus().isUnauthorized();
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
