package com.platform.gateway.web;

import com.platform.gateway.security.AcceptAnyJwtConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사무실 SSE 전용 라우트(agent-office-stream)도 CircuitBreaker fallback 계약을 타되, 장시간 스트림이
 * CB TimeLimiter나 전역 응답 타임아웃에 잘리지 않아야 한다. 전역 response-timeout을 1s로 줄이고
 * 이벤트 간격 1.5s·총 3s 넘게 흐르는 스트림이 끝까지 오는지 본다(TimeLimiter 기본 1s·전역 1s 둘 다 넘김).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s"})
@Import(AcceptAnyJwtConfig.class)
class AgentOfficeStreamRouteTest {

    static HttpServer agent;

    @BeforeAll
    static void start() throws IOException {
        agent = HttpServer.create(new InetSocketAddress(0), 0);
        agent.setExecutor(Executors.newCachedThreadPool());
        agent.createContext("/api/agent/office/stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); // chunked
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 3; i++) {
                    out.write(("data: tick" + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(1500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        agent.start();
    }

    @AfterAll
    static void stop() {
        agent.stop(0);
    }

    @DynamicPropertySource
    static void route(DynamicPropertyRegistry registry) {
        registry.add("AGENT_SERVICE_URI", () -> "http://localhost:" + agent.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .defaultHeader("Authorization", "Bearer any")
                .build();
    }

    @Test
    void longLivedStreamIsNotCut() {
        byte[] body = client().get().uri("/api/agent/office/stream").exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        assertThat(new String(body, StandardCharsets.UTF_8)).contains("tick0", "tick1", "tick2");
    }
}
