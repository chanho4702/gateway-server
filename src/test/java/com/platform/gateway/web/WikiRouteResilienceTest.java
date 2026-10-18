package com.platform.gateway.web;

import com.platform.gateway.security.AcceptAnyJwtConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 제품 라우트에 CircuitBreaker를 붙이면서 생길 수 있는 회귀를 막는다(P-10).
 * <ul>
 *   <li>Resilience4j 기본 TimeLimiter(1s)가 정상적인 느린 응답·큰 다운로드를 자르면 안 된다 — 제품 라우트는
 *       TimeLimiter를 끄고 httpclient response-timeout이 한도다.</li>
 *   <li>장시간 경로(PDF 내보내기·첨부 업로드·이슈 가져오기)는 라우트별 응답 타임아웃이 길다.</li>
 *   <li>다운스트림이 직접 낸 503 본문(예: org 불능 → 위키 503)은 fallback이 덮지 않고, GET도 재시도하지 않는다
 *       (실패 중인 서비스에 부하를 3배로 얹지 않는다 — 재시도는 연결 실패만).</li>
 * </ul>
 * 전역 response-timeout을 3s로 줄여 장시간 경로 차이를 짧게 재현한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=3s"})
@Import(AcceptAnyJwtConfig.class)
class WikiRouteResilienceTest {

    static HttpServer wiki;
    static final AtomicInteger unavailableHits = new AtomicInteger();

    @BeforeAll
    static void startWiki() throws IOException {
        wiki = HttpServer.create(new InetSocketAddress(0), 0);
        wiki.createContext("/api/wiki/slow", exchange -> respondAfter(exchange, 1500, 200, "[]"));
        wiki.createContext("/api/wiki/pages/7/export.pdf", exchange -> respondAfter(exchange, 4000, 200, "pdf"));
        wiki.createContext("/api/wiki/pages/7", exchange -> respondAfter(exchange, 4000, 200, "{}"));
        wiki.createContext("/api/wiki/unavailable", exchange -> {
            unavailableHits.incrementAndGet();
            respondAfter(exchange, 0, 503, "{\"error\":\"권한 서비스를 일시적으로 사용할 수 없습니다\"}");
        });
        wiki.createContext("/api/alm/projects/5/issues/import",
                exchange -> respondAfter(exchange, 4000, 200, "{}"));
        wiki.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); // 타임아웃 난 요청이 다음 테스트를 막지 않게
        wiki.start();
    }

    private static void respondAfter(HttpExchange exchange, long delayMs, int status, String body) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterAll
    static void stopWiki() {
        wiki.stop(0);
    }

    @DynamicPropertySource
    static void routeToStub(DynamicPropertyRegistry registry) {
        registry.add("WIKI_SERVICE_URI", () -> "http://localhost:" + wiki.getAddress().getPort());
        registry.add("ALM_SERVICE_URI", () -> "http://localhost:" + wiki.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void reset() {
        unavailableHits.set(0);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .defaultHeader("Authorization", "Bearer any")
                .build();
    }

    @Test
    void slowButHealthyResponseIsNotCutByDefaultTimeLimiter() {
        client().get().uri("/api/wiki/slow").exchange().expectStatus().isOk();
    }

    @Test
    void pdfExportGetsLongerResponseTimeout() {
        client().get().uri("/api/wiki/pages/7/export.pdf").exchange().expectStatus().isOk();
    }

    @Test
    void almIssueImportGetsLongerResponseTimeout() {
        client().post().uri("/api/alm/projects/5/issues/import").bodyValue("a,b").exchange()
                .expectStatus().isOk();
    }

    /** 일반 경로는 전역 타임아웃 그대로 — 넘으면 504 원문 대신 계약 503. */
    @Test
    void ordinaryPathStillTimesOutToContract503() {
        client().get().uri("/api/wiki/pages/7").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("wiki_unavailable");
    }

    @Test
    void downstream503BodyPassesThroughAndGetIsNotRetried() {
        client().get().uri("/api/wiki/unavailable").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("권한 서비스를 일시적으로 사용할 수 없습니다");
        assertThat(unavailableHits.get()).isEqualTo(1);
    }
}
