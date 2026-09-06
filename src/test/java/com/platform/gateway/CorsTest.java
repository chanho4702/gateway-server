package com.platform.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
class CorsTest {

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                // 기본 5초로는 컨텍스트 기동 직후 첫 요청이 빌드 머신 부하에 따라 타임아웃난다(실측).
                // 다른 서버 바인딩 테스트(SecurityConfigTest 등)와 같은 30초로 맞춘다.
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    void preflightFromFrontendIsAllowed() {
        client.method(HttpMethod.OPTIONS).uri("/api/board/posts")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    }
}
