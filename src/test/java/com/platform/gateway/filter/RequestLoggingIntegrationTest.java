package com.platform.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * 요청 로그·요청 ID가 라우팅 단계 밖의 응답에도 붙는지(P-12). 응답 헤더 {@code X-Request-Id}가 있다는 것은
 * 로깅 필터가 그 응답을 거쳤다는 증거다 — GlobalFilter였다면 보안 체인이 끊은 401과 PAT 필터가 끊은 401,
 * 라우트가 아닌 fallback 컨트롤러 응답에는 이 헤더가 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestLoggingIntegrationTest {

    @LocalServerPort
    int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    void securityRejected401CarriesRequestId() {
        client().get().uri("/api/wiki/spaces")
                .header("X-Request-Id", "req-401")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Request-Id", "req-401");
    }

    @Test
    void patRejected401CarriesRequestId() {
        client().get().uri("/api/me")
                .header("Authorization", "Bearer chanho_pat_" + "A".repeat(43))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    void nonRouteControllerResponseCarriesRequestId() {
        client().get().uri("/fallback/board")
                .header("X-Request-Id", "req-fallback")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("X-Request-Id", "req-fallback");
    }
}
