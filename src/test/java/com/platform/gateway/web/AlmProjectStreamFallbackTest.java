package com.platform.gateway.web;

import com.platform.gateway.security.AcceptAnyJwtConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/** alm-backend 불능 시 프로젝트 SSE 라우트도 일반 alm 라우트와 같은 계약 503. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ALM_SERVICE_URI=http://localhost:59995")
@Import(AcceptAnyJwtConfig.class)
class AlmProjectStreamFallbackTest {

    @LocalServerPort
    int port;

    @Test
    void streamRouteDownReturnsAlmFallback503() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build()
                .get().uri("/api/alm/projects/7/stream")
                .header("Authorization", "Bearer any")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo(FallbackController.MESSAGES.get("alm"));
    }
}
