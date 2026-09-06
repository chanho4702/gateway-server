package com.platform.gateway.platform;

import com.github.benmanes.caffeine.cache.Ticker;
import com.platform.gateway.security.TestJwt;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 판정. 여기서 지켜야 하는 계약은 두 줄이다 — {@code globalRoles}에 ADMIN이 있어야 통과하고,
 * org-service가 <b>불능일 때는 거부가 아니라 불능</b>이다(장애를 권한 문제로 둔갑시키지 않는다).
 */
class AdminGateTest {

    private static final String TOKEN = TestJwt.session("member-42");
    private static final String AUTHORIZATION = "Bearer " + TOKEN;

    private MockWebServer orgService;
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    @BeforeEach
    void start() throws Exception {
        orgService = new MockWebServer();
        orgService.start();
    }

    @AfterEach
    void stop() throws Exception {
        orgService.shutdown();
    }

    private AdminGate gate() {
        return gate(Duration.ofSeconds(10));
    }

    private AdminGate gate(Duration timeout) {
        return new AdminGate(WebClient.builder(), orgService.url("/").toString(), timeout, ticker);
    }

    private static MockResponse me(String... globalRoles) {
        String roles = globalRoles.length == 0 ? "" : "\"" + String.join("\",\"", globalRoles) + "\"";
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"memberId\":42,\"status\":\"ACTIVE\",\"globalRoles\":[" + roles + "]}");
    }

    @Test
    void globalRoles에_ADMIN이_있으면_통과한다() throws Exception {
        orgService.enqueue(me("ADMIN"));

        assertThat(gate().check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.ALLOWED);

        RecordedRequest recorded = orgService.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/org/me");
        // 사용자 토큰을 그대로 넘긴다 — 게이트웨이가 권한을 대신 판단하지 않는다.
        assertThat(recorded.getHeader("Authorization")).isEqualTo(AUTHORIZATION);
    }

    @Test
    void globalRoles가_비어_있으면_거부다() {
        orgService.enqueue(me());

        assertThat(gate().check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.DENIED);
    }

    @Test
    void ADMIN이_아닌_역할만_있으면_거부다() {
        orgService.enqueue(me("AUDITOR"));

        assertThat(gate().check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.DENIED);
    }

    @Test
    void org가_401_403을_주면_거부다() {
        orgService.enqueue(new MockResponse().setResponseCode(403).setBody("{\"error\":\"forbidden\"}"));

        assertThat(gate().check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.DENIED);
    }

    // --- 장애 전파 -------------------------------------------------------------

    @Test
    void org가_5xx면_거부가_아니라_불능이다() {
        orgService.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThat(gate().check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.UNAVAILABLE);
    }

    @Test
    void org가_응답하지_않으면_불능이다() {
        orgService.enqueue(me("ADMIN").setBodyDelay(2, TimeUnit.SECONDS));

        assertThat(gate(Duration.ofMillis(300)).check(AUTHORIZATION).block())
                .isEqualTo(AdminGate.Decision.UNAVAILABLE);
    }

    /** 200인데 본문이 JSON이 아니면 판정 근거가 없다 — 거부로 단정하지 않는다. */
    @Test
    void 본문을_읽을_수_없으면_불능이다() {
        orgService.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html").setBody("<html>proxy error</html>"));

        assertThat(gate().check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.UNAVAILABLE);
    }

    // --- 캐시 ----------------------------------------------------------------

    @Test
    void 같은_sub의_두_번째_판정은_org를_부르지_않는다() {
        orgService.enqueue(me("ADMIN"));
        AdminGate gate = gate();

        assertThat(gate.check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.ALLOWED);
        assertThat(gate.check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.ALLOWED);

        assertThat(orgService.getRequestCount()).isEqualTo(1);
    }

    @Test
    void 캐시는_30초_뒤에_다시_묻는다() {
        orgService.enqueue(me("ADMIN"));
        orgService.enqueue(me()); // 그 사이 권한이 회수됐다
        AdminGate gate = gate();

        assertThat(gate.check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.ALLOWED);
        nanos.addAndGet(Duration.ofSeconds(31).toNanos());
        assertThat(gate.check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.DENIED);

        assertThat(orgService.getRequestCount()).isEqualTo(2);
    }

    /** 불능은 캐시하지 않는다 — org가 살아난 즉시 정상 판정으로 돌아와야 한다. */
    @Test
    void 불능은_캐시하지_않는다() {
        orgService.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        orgService.enqueue(me("ADMIN"));
        AdminGate gate = gate();

        assertThat(gate.check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.UNAVAILABLE);
        assertThat(gate.check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.ALLOWED);

        assertThat(orgService.getRequestCount()).isEqualTo(2);
    }

    @Test
    void 다른_사용자는_캐시를_공유하지_않는다() {
        orgService.enqueue(me("ADMIN"));
        orgService.enqueue(me());
        AdminGate gate = gate();

        assertThat(gate.check(AUTHORIZATION).block()).isEqualTo(AdminGate.Decision.ALLOWED);
        String other = "Bearer " + TestJwt.pat("member-9", List.of("admin"));
        assertThat(gate.check(other).block()).isEqualTo(AdminGate.Decision.DENIED);

        assertThat(orgService.getRequestCount()).isEqualTo(2);
    }
}
