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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 헬스 집계. 프로브 대상은 MockWebServer 한 대에 경로로 나눠 붙인다 — 어떤 응답이 어떤 상태로
 * 옮겨지는지, <b>시간 예산 두 겹</b>(컴포넌트 3초·집계 5초)이 지켜지는지, 그리고 캐시 한 주기에
 * 프로브가 한 번만 나가는지가 이 스위트의 관심사다.
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
                return record(request.getPath());
            }
        });
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    // --- 도우미 ---------------------------------------------------------------

    private MockResponse record(String path) {
        hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
        MockResponse response = routes.get(path);
        return response == null ? new MockResponse().setResponseCode(404) : response;
    }

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
        return aggregator(targets, Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofSeconds(20));
    }

    private HealthAggregator aggregator(Map<String, String> targets, Duration probeTimeout,
                                        Duration aggregateTimeout, Duration cacheTtl) {
        return new HealthAggregator(WebClient.builder(), targets, probeTimeout, aggregateTimeout,
                cacheTtl, "9.9.9");
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

    /** 버전은 UP인 컴포넌트에만 붙인다 — DOWN 행에 버전이 있으면 "떠 있는데 아픈 것"과 헷갈린다. */
    @Test
    void DOWN이면_버전을_싣지_않는다() {
        route("/alm/actuator/health", actuator("DOWN", "DOWN", "DOWN"));
        route("/alm/actuator/info", json(200, "{\"build\":{\"version\":\"1.0.0\"}}"));

        HealthReport report = aggregator(Map.of("alm-backend", url("/alm/actuator/health"))).report().block();

        assertThat(component(report, "alm-backend").version()).isNull();
    }

    @Test
    void 응답이_없으면_DOWN이고_사유는_타임아웃이다() {
        route("/agent/actuator/health", actuator("UP", "UP", "UP").setBodyDelay(3, TimeUnit.SECONDS));

        HealthReport report = aggregator(Map.of("agent-service", url("/agent/actuator/health")),
                Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofSeconds(20)).report().block();

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

    // --- actuator 없는 대상(eureka·search-service) --------------------------------

    /** eureka에는 actuator가 없다 — 404면 루트 200으로 살아 있음을 확인한다. */
    @Test
    void actuator가_없는_서비스는_루트_200으로_판정한다() {
        route("/", new MockResponse().setResponseCode(200).setBody("<html>eureka</html>"));

        HealthReport report = aggregator(Map.of("eureka", url("/actuator/health"))).report().block();

        ComponentHealth eureka = component(report, "eureka");
        assertThat(eureka.status()).isEqualTo("UP");
        assertThat(eureka.detail()).isEqualTo("actuator 없음");
    }

    @Test
    void 검색_서비스도_같은_방식으로_표에_들어간다() {
        route("/", new MockResponse().setResponseCode(200).setBody("search"));

        HealthReport report = aggregator(Map.of("search-service", url("/actuator/health"))).report().block();

        ComponentHealth search = component(report, "search-service");
        assertThat(search.name()).isEqualTo("검색 서비스");
        assertThat(search.group()).isEqualTo("service");
        assertThat(search.status()).isEqualTo("UP");
    }

    /**
     * 404를 한 번 확인한 대상에는 다음 주기부터 {@code /actuator/info}를 던지지 않는다 —
     * 20초마다 확정된 404를 낭비할 이유가 없다.
     */
    @Test
    void actuator가_없다고_확인되면_다음_주기부터_info를_부르지_않는다() throws Exception {
        route("/", new MockResponse().setResponseCode(200).setBody("search"));
        // 캐시 TTL을 최소로 두고 그보다 넉넉히 기다려 두 번째 수집을 실제로 일으킨다.
        HealthAggregator aggregator = aggregator(Map.of("search-service", url("/actuator/health")),
                Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofMillis(1));

        assertThat(component(aggregator.report().block(), "search-service").status()).isEqualTo("UP");
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(component(aggregator.report().block(), "search-service").status()).isEqualTo("UP");

        assertThat(hits("/actuator/health")).as("health는 매 주기").isEqualTo(2);
        assertThat(hits("/")).as("루트 폴백도 매 주기").isEqualTo(2);
        assertThat(hits("/actuator/info")).as("info는 첫 주기 한 번뿐").isEqualTo(1);
    }

    // --- 시간 예산 -------------------------------------------------------------

    /**
     * health와 info는 병렬이어야 한다. 순차라면 컴포넌트 하나가 프로브 상한의 두 배를 쓴다.
     * 시계 대신 래치로 못박는다 — health 응답을 info 요청이 도착할 때까지 붙잡아 두고,
     * 그래도 둘 다 성공하면 두 요청이 동시에 떠 있었다는 뜻이다(순차였다면 여기서 굶는다).
     */
    @Test
    void health와_info를_병렬로_받는다() throws Exception {
        CountDownLatch infoArrived = new CountDownLatch(1);
        route("/wiki/actuator/health", actuator("UP", "UP", "UP"));
        route("/wiki/actuator/info", json(200, "{\"build\":{\"version\":\"2.0.0\"}}"));
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getPath();
                if (path.endsWith("/actuator/info")) {
                    infoArrived.countDown();
                } else if (path.endsWith("/actuator/health")) {
                    infoArrived.await(3, TimeUnit.SECONDS); // 병렬이면 곧 풀린다
                }
                return record(path);
            }
        });

        HealthReport report = aggregator(Map.of("wiki-backend", url("/wiki/actuator/health")),
                Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(20)).report().block();

        assertThat(infoArrived.getCount()).as("info 요청이 health 응답 전에 도착해야 한다").isZero();
        ComponentHealth wiki = component(report, "wiki-backend");
        assertThat(wiki.status()).isEqualTo("UP");
        assertThat(wiki.version()).isEqualTo("2.0.0");
    }

    /** 느린 컴포넌트 하나가 표 전체를 붙잡지 않는다 — 못 받은 행만 UNKNOWN이 된다. */
    @Test
    void 집계_상한을_넘기면_받은_것만으로_표를_만든다() {
        route("/wiki/actuator/health", actuator("UP", "UP", "UP"));
        route("/alm/actuator/health", actuator("UP", "UP", "UP").setBodyDelay(4, TimeUnit.SECONDS));

        HealthReport report = aggregator(new LinkedHashMap<>(Map.of(
                        "wiki-backend", url("/wiki/actuator/health"),
                        "alm-backend", url("/alm/actuator/health"))),
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(20)).report().block();

        assertThat(component(report, "wiki-backend").status()).isEqualTo("UP");
        ComponentHealth alm = component(report, "alm-backend");
        assertThat(alm.status()).isEqualTo("UNKNOWN");
        assertThat(alm.detail()).isEqualTo("집계 시간 초과");
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
