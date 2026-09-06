package com.platform.gateway.platform;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** auth-server {@code /internal/pat/stats} 프록시 — 통과·불능·캐시 셋만 본다. */
class AuthStatsClientTest {

    private static final String SECRET = "test-internal-secret";
    private static final String BODY =
            "{\"activeTokens\":7,\"usersWithTokens\":3,\"expiringWithin7Days\":1}";

    private MockWebServer authServer;

    @BeforeEach
    void start() throws Exception {
        authServer = new MockWebServer();
        authServer.start();
    }

    @AfterEach
    void stop() throws Exception {
        authServer.shutdown();
    }

    private AuthStatsClient client(String secret) {
        return new AuthStatsClient(WebClient.builder(), authServer.url("/").toString(), secret,
                Duration.ofSeconds(10), Duration.ofSeconds(60));
    }

    private static MockResponse ok() {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(BODY);
    }

    @Test
    void 내부_시크릿을_붙여_호출하고_본문을_그대로_돌려준다() throws Exception {
        authServer.enqueue(ok());

        Map<String, Object> stats = client(SECRET).stats().block();

        assertThat(stats).containsEntry("activeTokens", 7)
                .containsEntry("usersWithTokens", 3)
                .containsEntry("expiringWithin7Days", 1);

        RecordedRequest recorded = authServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/internal/pat/stats");
        assertThat(recorded.getHeader("X-Internal-Secret")).isEqualTo(SECRET);
    }

    @Test
    void 두_번째_호출은_캐시에서_나간다() {
        authServer.enqueue(ok());
        AuthStatsClient client = client(SECRET);

        assertThat(client.stats().block()).isNotNull();
        assertThat(client.stats().block()).isNotNull();

        assertThat(authServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void auth가_5xx면_빈_결과다() {
        authServer.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThat(client(SECRET).stats().block()).isNull(); // 호출자가 503 auth_unavailable로 옮긴다
    }

    /** 비밀이 없으면 호출해봐야 403이다 — PAT 교환과 같은 fail-closed. */
    @Test
    void 시크릿이_없으면_호출조차_하지_않는다() {
        assertThat(client("").stats().block()).isNull();

        assertThat(authServer.getRequestCount()).isZero();
    }
}
