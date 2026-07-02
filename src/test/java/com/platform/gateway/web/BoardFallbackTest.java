package com.platform.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/** board-service 다운 시 무한대기/원시 5xx 대신 fallback 503 JSON. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"BOARD_SERVICE_URI=http://localhost:59999"}) // 닫힌 포트 → connection refused
class BoardFallbackTest {

    @LocalServerPort
    int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    void boardDownReturnsFallback503Json() {
        client().get().uri("/api/board/posts").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("board_unavailable");
    }

    @Test
    void fallbackEndpointDirectly() {
        client().get().uri("/fallback/board").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("board_unavailable");
    }
}
