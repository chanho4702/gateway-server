package com.platform.gateway.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 회귀 방지: Resilience4j 기본 TimeLimiter(1s)가 CircuitBreaker 라우트를 캡해서
 * 1초 넘는 '정상' 응답까지 fallback 503으로 잘리면 안 된다.
 * (전역 response-timeout 10s가 실제 한도여야 함 — resilience4j.timelimiter로 상향 필요.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SlowBoardDownstreamTest {

    static HttpServer slowBoard;

    @BeforeAll
    static void startSlowBoard() throws IOException {
        slowBoard = HttpServer.create(new InetSocketAddress(0), 0);
        slowBoard.createContext("/api/board/posts", exchange -> {
            try {
                Thread.sleep(1500); // 기본 TimeLimiter(1s)보다 길고 response-timeout(10s)보다 짧게
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        slowBoard.start();
    }

    @AfterAll
    static void stopSlowBoard() {
        slowBoard.stop(0);
    }

    @DynamicPropertySource
    static void routeToSlowBoard(DynamicPropertyRegistry registry) {
        registry.add("BOARD_SERVICE_URI", () -> "http://localhost:" + slowBoard.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    @Test
    void slowButHealthyDownstreamIsNotCutToFallback() {
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build()
                .get().uri("/api/board/posts").exchange()
                .expectStatus().isOk();
    }
}
