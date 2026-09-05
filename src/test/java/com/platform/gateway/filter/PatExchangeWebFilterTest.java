package com.platform.gateway.filter;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebHandler;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAT 교환 필터 단독 검증. auth-server는 MockWebServer 스텁, 다운스트림은 Authorization 헤더를
 * 그대로 기록하는 WebHandler로 대체한다 — 실제 JWKS/보안체인 없이 "무엇이 다운스트림에 도달하는가"만 본다.
 */
class PatExchangeWebFilterTest {

    private static final String PAT = "chanho_pat_" + "A".repeat(43);
    private static final String JWT = "header.payload.signature";

    private MockWebServer authServer;
    private final AtomicReference<String> downstreamAuthHeader = new AtomicReference<>();
    private final AtomicReference<Boolean> downstreamCalled = new AtomicReference<>(false);

    @BeforeEach
    void startAuthServer() throws Exception {
        authServer = new MockWebServer();
        authServer.start();
    }

    @AfterEach
    void stopAuthServer() throws Exception {
        authServer.shutdown();
    }

    // --- 도우미 -------------------------------------------------------------

    private WebTestClient clientWithSecret(String secret) {
        PatExchangeClient exchangeClient =
                new PatExchangeClient(WebClient.builder(), authServer.url("/").toString(), secret);
        WebHandler downstream = exchange -> {
            downstreamCalled.set(true);
            downstreamAuthHeader.set(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        return WebTestClient.bindToWebHandler(downstream)
                .webFilter(new PatExchangeWebFilter(exchangeClient))
                .configureClient()
                .responseTimeout(Duration.ofSeconds(20))
                .build();
    }

    private WebTestClient client() {
        return clientWithSecret("test-internal-secret");
    }

    private static MockResponse exchangeOk(String jwt, long expiresInSeconds) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"accessToken\":\"" + jwt + "\",\"expiresInSeconds\":" + expiresInSeconds + "}");
    }

    private static MockResponse errorResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    // --- 성공 경로 -----------------------------------------------------------

    @Test
    void patIsExchangedAndDownstreamSeesPlatformJwt() throws Exception {
        authServer.enqueue(exchangeOk(JWT, 300));

        client().get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer " + JWT);

        RecordedRequest recorded = authServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/internal/pat/exchange");
        assertThat(recorded.getHeader("X-Internal-Secret")).isEqualTo("test-internal-secret");
        assertThat(recorded.getBody().readUtf8()).contains(PAT);
    }

    @Test
    void bearerSchemeIsCaseInsensitive() {
        authServer.enqueue(exchangeOk(JWT, 300));

        client().get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "bEaReR " + PAT)
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer " + JWT);
    }

    @Test
    void secondCallWithinCacheTtlDoesNotHitAuthServer() {
        authServer.enqueue(exchangeOk(JWT, 300));
        WebTestClient client = client();

        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isOk();
        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer " + JWT);
        assertThat(authServer.getRequestCount()).isEqualTo(1); // 두 번째는 캐시 히트
    }

    /**
     * 캐시 항목의 TTL(60s)이 남아 있어도 JWT 자체가 만료 30초 안이면 재사용하지 않는다.
     * auth-server가 20초짜리 JWT를 주면 첫 응답부터 이미 가드 안이라 두 번째 요청은 다시 교환한다.
     */
    @Test
    void jwtCloseToExpiryIsNotReusedFromCache() {
        authServer.enqueue(exchangeOk(JWT, 20));
        authServer.enqueue(exchangeOk("second." + JWT, 300));
        WebTestClient client = client();

        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isOk();
        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange().expectStatus().isOk();

        assertThat(authServer.getRequestCount()).isEqualTo(2);
        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer second." + JWT);
    }

    // --- 실패 경로 -----------------------------------------------------------

    @Test
    void invalidPatReturns401AndIsNegativelyCached() {
        authServer.enqueue(errorResponse(401, "{\"error\":\"invalid_token\"}"));
        WebTestClient client = client();

        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody().jsonPath("$.error").isEqualTo("invalid_token");

        // 부정 캐시 10s — 두 번째 요청은 auth-server를 때리지 않는다(무차별 대입 완화).
        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("invalid_token");

        assertThat(authServer.getRequestCount()).isEqualTo(1);
        assertThat(downstreamCalled.get()).isFalse();
    }

    @Test
    void authServerErrorReturns503AndIsNotCached() {
        authServer.enqueue(errorResponse(500, "boom"));
        authServer.enqueue(errorResponse(500, "boom"));
        WebTestClient client = client();

        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("auth_unavailable");

        // 장애는 곧 풀릴 수 있으므로 캐시하지 않는다 — 다음 요청은 다시 시도한다.
        client.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isEqualTo(503);

        assertThat(authServer.getRequestCount()).isEqualTo(2);
        assertThat(downstreamCalled.get()).isFalse();
    }

    /** 비밀 불일치(403)는 배포 설정 오류다 — 사용자 토큰을 무효라고 단정하지 않고 불능으로 올린다. */
    @Test
    void secretMismatchReturns503NotInvalidToken() {
        authServer.enqueue(errorResponse(403, "{\"error\":\"forbidden\"}"));

        client().get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("auth_unavailable");
    }

    @Test
    void authServerTimeoutReturns503() {
        authServer.enqueue(exchangeOk(JWT, 300).setBodyDelay(3, TimeUnit.SECONDS)); // 클라이언트 타임아웃 2s

        client().get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("auth_unavailable");

        assertThat(downstreamCalled.get()).isFalse();
    }

    @Test
    void emptyInternalSecretRejectsPatWithoutCallingAuthServer() {
        clientWithSecret("").get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("invalid_token");

        assertThat(authServer.getRequestCount()).isZero(); // fail-closed — 교환 시도조차 하지 않는다
        assertThat(downstreamCalled.get()).isFalse();
    }

    // --- 통과 경로(건드리지 않는다) --------------------------------------------

    @Test
    void plainJwtBearerPassesThroughUntouched() {
        client().get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT)
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Bearer " + JWT);
        assertThat(authServer.getRequestCount()).isZero();
    }

    @Test
    void requestWithoutAuthorizationHeaderPassesThroughUntouched() {
        client().get().uri("/api/board/posts")
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamCalled.get()).isTrue();
        assertThat(downstreamAuthHeader.get()).isNull();
        assertThat(authServer.getRequestCount()).isZero();
    }

    @Test
    void nonBearerSchemeIsNotTreatedAsPat() {
        client().get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + PAT)
                .exchange()
                .expectStatus().isOk();

        assertThat(downstreamAuthHeader.get()).isEqualTo("Basic " + PAT);
        assertThat(authServer.getRequestCount()).isZero();
    }

    // --- 접두사 판별 순수 함수 --------------------------------------------------

    @Test
    void extractPatTokenRecognizesOnlyPatPrefixedBearerTokens() {
        assertThat(PatExchangeWebFilter.extractPatToken("Bearer " + PAT)).isEqualTo(PAT);
        assertThat(PatExchangeWebFilter.extractPatToken("BEARER " + PAT)).isEqualTo(PAT);
        assertThat(PatExchangeWebFilter.extractPatToken("Bearer " + JWT)).isNull();
        assertThat(PatExchangeWebFilter.extractPatToken("Bearer")).isNull();
        assertThat(PatExchangeWebFilter.extractPatToken("")).isNull();
        assertThat(PatExchangeWebFilter.extractPatToken(null)).isNull();
        assertThat(PatExchangeWebFilter.extractPatToken(PAT)).isNull(); // 스킴 없음
    }

}
