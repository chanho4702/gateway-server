package com.platform.gateway.web;

import com.platform.gateway.security.AcceptAnyJwtConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/** agent-service 불능 시 SSE 전용 라우트도 일반 agent 라우트와 같은 계약 503. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "AGENT_SERVICE_URI=http://localhost:59996")
@Import(AcceptAnyJwtConfig.class)
class AgentOfficeStreamFallbackTest {

    @LocalServerPort
    int port;

    @Test
    void streamRouteDownReturnsAgentFallback503() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build()
                .get().uri("/api/agent/office/stream")
                .header("Authorization", "Bearer any")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo(FallbackController.MESSAGES.get("agent"));
    }
}
