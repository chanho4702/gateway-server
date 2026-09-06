package com.platform.gateway.platform;

import com.platform.gateway.platform.HealthReport.ComponentHealth;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 헬스 집계. 프로브 대상은 MockWebServer 한 대에 경로로 나눠 붙인다 — 어떤 응답이 어떤 상태로
 * 옮겨지는지, 그리고 <b>캐시 한 주기에 프로브가 한 번만 나가는지</b>가 이 스위트의 관심사다.
 */
class HealthAggregatorTest {

    private MockWebServer server;
    private final Map<String, MockResponse> routes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
                MockResponse response = routes.get(path);
                return response == null ? new MockResponse().setResponseCode(404) : response;
            }
        });
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    // --- 도우미 ---------------------------------------------------------------

    private void route(String path, MockResponse response) {
        routes.put(path, response);
    }

    private int hits(String path) {
        AtomicInteger counter = hits.get(path);
        return counter == null ? 0 : counter.get();
    }

    private String url(String path) {
        return server.url(path).toString();
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    /** Spring Actuator {@code show-details: always} 응답 모양. */
    private static MockResponse actuator(String status, String db, String redis) {
        return json("UP".equals(status) ? 200 : 503,
                "{\"status\":\"" + status + "\",\"components\":{"
                        + "\"db\":{\"status\":\"" + db + "\"},"
                        + "\"redis\":{\"status\":\"" + redis + "\"}}}");
    }

    private HealthAggregator aggregator(Map<String, String> targets) {
        return aggregator(targets, Duration.ofSeconds(5));
    }

    private HealthAggregator aggregator(Map<String, String> targets, Duration probeTimeout) {
        return new HealthAggregator(WebClient.builder(), targets, probeTimeout,
                Duration.ofSeconds(20), "9.9.9");
    }

    private static ComponentHealth component(HealthReport report, String id) {
        return report.components().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError(id + " 행이 없다"));
    }

    // --- 상태 매핑 -------------------------------------------------------------

    @Test
    void actuator_UP은_UP이고_info의_build_version을_싣는다() {
        route("/wiki/actuator/health", actuator("UP", "UP", "UP"));
        route("/wiki/actuator/info", json(200, "{\"build\":{\"version\":\"1.4.0\",\"name\":\"wiki\"}}"));

        HealthReport report = aggregator(Map.of("wiki-backend", url("/wiki/actuator/health"))).report().block();

        ComponentHealth wiki = component(report, "wiki-backend");
        assertThat(wiki.status()).isEqualTo("UP");
        assertThat(wiki.version()).isEqualTo("1.4.0");
        assertThat(wiki.name()).isEqualTo("위키 백엔드");
        assertThat(wiki.group()).isEqualTo("service");
        assertThat(wiki.latencyMs()).isNotNull();
    }

    @Test
    void actuator가_DOWN이면_어떤_하위_컴포넌트_때문인지_사유에_남는다() {
        route("/alm/actuator/health", actuator("DOWN", "DOWN", "UP"));

        HealthReport report = aggregator(Map.of("alm-backend", url("/alm/actuator/health"))).report().block();

        ComponentHealth alm = component(report, "alm-backend");
        assertThat(alm.status()).isEqualTo("DOWN");
        assertThat(alm.detail()).contains("DOWN").contains("db");
    }

    /** 상태가 DOWN이면 버전은 묻지 않는다 — 죽은 서비스에 프로브를 하나 더 보내지 않는다. */
    @Test
    void DOWN이면_info를_부르지_않는다() {
        route("/alm/actuator/health", actuator("DOWN", "DOWN", "DOWN"));
        route("/alm/actuator/info", json(200, "{\"build\":{\"version\":\"1.0.0\"}}"));

        aggregator(Map.of("alm-backend", url("/alm/actuator/health"))).report().block();

        assertThat(hits("/alm/actuator/info")).isZero();
    }

    @Test
    void 응답이_없으면_DOWN이고_사유는_타임아웃이다() {
        route("/agent/actuator/health", actuator("UP", "UP", "UP").setBodyDelay(3, TimeUnit.SECONDS));

        HealthReport report = aggregator(Map.of("agent-service", url("/agent/actuator/health")),
                Duration.ofSeconds(1)).report().block();

        ComponentHealth agent = component(report, "agent-service");
        assertThat(agent.status()).isEqualTo("DOWN");
        assertThat(agent.detail()).isEqualTo("timeout 1s");
    }

    @Test
    void 연결이_안_되면_DOWN이다() {
        // 아무도 듣지 않는 포트 — 즉시 connection refused.
        HealthReport report = aggregator(Map.of("board-service", "http://127.0.0.1:1/actuator/health"))
                .report().block();

        ComponentHealth board = component(report, "board-service");
        assertThat(board.status()).isEqualTo("DOWN");
        assertThat(board.detail()).isNotBlank();
    }

    @Test
    void 단순_2xx_프로브는_그대로_UP이고_아니면_DOWN이다() {
        route("/collab/health", new MockResponse().setResponseCode(200).setBody("ok"));
        route("/loki/ready", new MockResponse().setResponseCode(503).setBody("not ready"));

        HealthReport report = aggregator(new LinkedHashMap<>(Map.of(
                "collaboration-service", url("/collab/health"),
                "loki", url("/loki/ready")))).report().block();

        assertThat(component(report, "collaboration-service").status()).isEqualTo("UP");
        assertThat(component(report, "loki").status()).isEqualTo("DOWN");
        assertThat(component(report, "loki").detail()).isEqualTo("HTTP 503");
    }

    @Test
    void 오픈서치는_red만_DEGRADED다() {
        route("/os/_cluster/health", json(200, "{\"status\":\"yellow\"}"));
        assertThat(component(aggregator(Map.of("opensearch", url("/os/_cluster/health"))).report().block(),
                "opensearch").status()).isEqualTo("UP");

        routes.clear();
        route("/os/_cluster/health", json(200, "{\"status\":\"red\"}"));
        ComponentHealth red = component(
                aggregator(Map.of("opensearch", url("/os/_cluster/health"))).report().block(), "opensearch");
        assertThat(red.status()).isEqualTo("DEGRADED");
        assertThat(red.detail()).isEqualTo("cluster red");
    }

    /** eureka에는 actuator가 없다 — 404면 루트 200으로 살아 있음을 확인한다. */
    @Test
    void actuator가_없는_서비스는_루트_200으로_판정한다() {
        route("/", new MockResponse().setResponseCode(200).setBody("<html>eureka</html>"));

        HealthReport report = aggregator(Map.of("eureka", url("/actuator/health"))).report().block();

        ComponentHealth eureka = component(report, "eureka");
        assertThat(eureka.status()).isEqualTo("UP");
        assertThat(eureka.detail()).isEqualTo("actuator 없음");
    }

    // --- 파생 행 --------------------------------------------------------------

    @Test
    void postgres_redis는_서비스들의_components에서_파생된다() {
        route("/wiki/actuator/health", actuator("UP", "UP", "UP"));
        route("/alm/actuator/health", actuator("DOWN", "DOWN", "UP"));

        HealthReport report = aggregator(new LinkedHashMap<>(Map.of(
                "wiki-backend", url("/wiki/actuator/health"),
                "alm-backend", url("/alm/actuator/health")))).report().block();

        ComponentHealth postgres = component(report, "postgres");
        assertThat(postgres.status()).isEqualTo("DEGRADED"); // 하나만 DOWN
        assertThat(postgres.detail()).isEqualTo("1/2 서비스에서 DOWN");
        assertThat(postgres.group()).isEqualTo("infra");

        assertThat(component(report, "redis").status()).isEqualTo("UP");
    }

    @Test
    void 모든_서비스가_db_DOWN이면_postgres도_DOWN이다() {
        route("/wiki/actuator/health", actuator("DOWN", "DOWN", "UP"));

        HealthReport report = aggregator(Map.of("wiki-backend", url("/wiki/actuator/health"))).report().block();

        assertThat(component(report, "postgres").status()).isEqualTo("DOWN");
    }

    /** 아무 서비스도 답하지 않으면 "DB가 죽었다"고 말할 근거가 없다 — UNKNOWN이지 DOWN이 아니다. */
    @Test
    void 보고한_서비스가_없으면_UNKNOWN이다() {
        HealthReport report = aggregator(Map.<String, String>of()).report().block();

        assertThat(component(report, "postgres").status()).isEqualTo("UNKNOWN");
        assertThat(component(report, "redis").status()).isEqualTo("UNKNOWN");
    }

    // --- 표 구성·캐시 ----------------------------------------------------------

    @Test
    void 게이트웨이_자신은_항상_UP이고_build_version을_쓴다() {
        HealthReport report = aggregator(Map.<String, String>of()).report().block();

        ComponentHealth self = component(report, "gateway-server");
        assertThat(self.status()).isEqualTo("UP");
        assertThat(self.version()).isEqualTo("9.9.9");
        assertThat(report.cacheTtlSeconds()).isEqualTo(20);
        assertThat(report.checkedAt()).isNotNull();
    }

    /** 주소를 비우면 그 행 자체가 없다 — OpenSearch를 내린 배포에서 항상 DOWN인 행이 뜨지 않게. */
    @Test
    void 주소가_없는_대상은_표에서_빠진다() {
        HealthReport report = aggregator(Map.<String, String>of()).report().block();

        assertThat(report.components()).extracting(ComponentHealth::id)
                .containsExactly("gateway-server", "postgres", "redis");
    }

    @Test
    void 캐시_주기_안의_두_번째_호출은_프로브를_다시_돌리지_않는다() {
        route("/wiki/actuator/health", actuator("UP", "UP", "UP"));
        route("/wiki/actuator/info", json(200, "{\"build\":{\"version\":\"1.4.0\"}}"));
        HealthAggregator aggregator = aggregator(Map.of("wiki-backend", url("/wiki/actuator/health")));

        HealthReport first = aggregator.report().block();
        HealthReport second = aggregator.report().block();

        assertThat(hits("/wiki/actuator/health")).isEqualTo(1);
        assertThat(second.checkedAt()).isEqualTo(first.checkedAt());
    }
}
