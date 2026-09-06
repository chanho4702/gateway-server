package com.platform.gateway.platform;

import com.platform.gateway.platform.HealthCatalog.Spec;
import com.platform.gateway.platform.HealthReport.ComponentHealth;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 플랫폼 전체 헬스 집계. 관리자 대시보드가 보는 한 장의 표를 만든다.
 *
 * <p><b>부하 원칙</b>(설계 §0)이 이 클래스의 형태를 정한다 — 결과를 {@code cacheTtl}(기본 20초) 동안
 * 캐시하고, 그 사이 들어온 동시 요청은 진행 중인 프로브 하나를 공유한다({@code Mono.cache}). 화면을
 * 몇 명이 보든 각 서비스가 받는 프로브는 20초당 1회다.
 *
 * <p><b>시간 예산</b>은 두 겹이다. 컴포넌트 하나는 {@code probeTimeout}(3초)을 넘지 않는다 —
 * {@code /actuator/health}와 {@code /actuator/info}를 <i>순차가 아니라 병렬</i>로 받는 이유가 이것이다
 * (순차면 느린 서비스 하나가 6초를 쓴다). 그 위에 집계 전체 상한 {@code aggregateTimeout}(5초)이 있고,
 * 넘기면 그때까지 답한 것만으로 표를 만들고 못 받은 행은 UNKNOWN으로 둔다 — 대시보드가 통째로
 * 멈추는 것보다 낫다.
 *
 * <p>postgres·redis 행에는 프로브가 없다. Spring 서비스들이 이미 자기 health에
 * {@code components.db}·{@code components.redis}를 싣고 있으므로 그것을 모아 파생한다 — DB에 직접
 * 커넥션을 여는 프로브를 20초마다 돌리는 것보다 정확하고 싸다.
 *
 * <p>응답 본문은 {@code ObjectMapper}를 직접 만들지 않고 WebClient 코덱으로 {@code Map}으로 받는다 —
 * 이 리포는 Jackson 2·3이 함께 캐시에 있는 환경에서 빌드되므로 databind 패키지 이름에 기대지 않는다.
 */
@Component
public class HealthAggregator {

    static final String UP = "UP";
    static final String DEGRADED = "DEGRADED";
    static final String DOWN = "DOWN";
    static final String UNKNOWN = "UNKNOWN";

    private static final Duration FAILURE_TTL = Duration.ofSeconds(5);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final Map<String, String> targets;
    private final Duration probeTimeout;
    private final Duration aggregateTimeout;
    private final Duration cacheTtl;
    private final String selfVersion;
    private final Mono<HealthReport> cached;

    /**
     * Actuator가 없다고 확인된 대상(eureka·search-service). 다음 주기부터는 {@code /actuator/info}를
     * 아예 부르지 않는다 — 어차피 404이고, 20초마다 낭비할 이유가 없다. 상태가 404를 벗어나면 지운다.
     */
    private final Set<String> actuatorAbsent = ConcurrentHashMap.newKeySet();

    @Autowired
    public HealthAggregator(PlatformHealthProperties properties, ObjectProvider<BuildProperties> buildProperties) {
        this(WebClient.builder(), properties.getTargets(), properties.getProbeTimeout(),
                properties.getAggregateTimeout(), properties.getCacheTtl(),
                versionOf(buildProperties.getIfAvailable()));
    }

    /** 테스트용 — MockWebServer 주소 맵과 짧은 타임아웃·TTL을 직접 준다. */
    HealthAggregator(WebClient.Builder builder, Map<String, String> targets, Duration probeTimeout,
                     Duration aggregateTimeout, Duration cacheTtl, String selfVersion) {
        this.webClient = builder.build();
        this.targets = Map.copyOf(targets);
        this.probeTimeout = probeTimeout;
        this.aggregateTimeout = aggregateTimeout;
        this.cacheTtl = cacheTtl;
        this.selfVersion = selfVersion;
        // 값은 TTL만큼, 실패는 짧게 캐시한다. 진행 중인 수집은 후발 구독자가 공유한다(중복 프로브 방지).
        this.cached = Mono.defer(this::collect)
                .cache(value -> this.cacheTtl, error -> FAILURE_TTL, () -> FAILURE_TTL);
    }

    private static String versionOf(BuildProperties buildProperties) {
        return buildProperties == null ? null : buildProperties.getVersion();
    }

    /** 캐시된 집계 결과. TTL이 지났으면 이 구독이 새 수집을 시작하고 동시 구독자는 그것을 함께 기다린다. */
    public Mono<HealthReport> report() {
        return cached;
    }

    // --- 수집 ---------------------------------------------------------------

    private Mono<HealthReport> collect() {
        Instant checkedAt = Instant.now();
        List<Spec> probed = HealthCatalog.SPECS.stream().filter(this::isProbed).toList();
        // 상한을 넘겨도 그때까지 답한 것은 살린다 — Flux 전체를 버리면 표가 통째로 빈다.
        List<ProbeResult> collected = new CopyOnWriteArrayList<>();
        return Flux.fromIterable(probed)
                .flatMap(spec -> probe(spec, targets.get(spec.id())).doOnNext(collected::add))
                .then()
                .timeout(aggregateTimeout)
                .onErrorResume(TimeoutException.class, e -> Mono.empty())
                .then(Mono.fromSupplier(() -> assemble(checkedAt, collected)));
    }

    /** 주소가 설정된 것만 프로브한다 — 값을 비우면 그 행 자체가 사라진다(OpenSearch 없는 배포 등). */
    private boolean isProbed(Spec spec) {
        return switch (spec.probe()) {
            case SELF, DERIVED_DB, DERIVED_REDIS -> false;
            default -> {
                String url = targets.get(spec.id());
                yield url != null && !url.isBlank();
            }
        };
    }

    private HealthReport assemble(Instant checkedAt, List<ProbeResult> results) {
        Map<String, ProbeResult> byId = new LinkedHashMap<>();
        results.forEach(result -> byId.put(result.id(), result));

        List<ComponentHealth> components = new ArrayList<>();
        for (Spec spec : HealthCatalog.SPECS) {
            switch (spec.probe()) {
                case SELF -> components.add(new ComponentHealth(spec.id(), spec.name(), spec.group(),
                        UP, 0L, selfVersion, null));
                case DERIVED_DB -> components.add(derive(spec, results, ProbeResult::dbStatus));
                case DERIVED_REDIS -> components.add(derive(spec, results, ProbeResult::redisStatus));
                default -> {
                    ProbeResult result = byId.get(spec.id());
                    if (result != null) {
                        components.add(new ComponentHealth(spec.id(), spec.name(), spec.group(),
                                result.status(), result.latencyMs(), result.version(), result.detail()));
                    } else if (isProbed(spec)) {
                        // 설정은 됐는데 집계 상한 안에 답이 오지 않았다. 행을 지우면 "설정 안 됨"과
                        // 구분이 안 되므로 모른다고 말한다.
                        components.add(new ComponentHealth(spec.id(), spec.name(), spec.group(),
                                UNKNOWN, null, null, "집계 시간 초과"));
                    }
                }
            }
        }
        return new HealthReport(checkedAt, cacheTtl.toSeconds(), List.copyOf(components));
    }

    /** Spring 서비스들이 보고한 같은 의존(db·redis) 상태를 하나의 행으로 접는다. */
    private ComponentHealth derive(Spec spec, List<ProbeResult> results, Function<ProbeResult, String> extract) {
        List<String> reported = results.stream().map(extract).filter(s -> s != null && !s.isBlank()).toList();
        if (reported.isEmpty()) {
            return new ComponentHealth(spec.id(), spec.name(), spec.group(), UNKNOWN, null, null,
                    "보고한 서비스 없음");
        }
        long down = reported.stream().filter(s -> !UP.equalsIgnoreCase(s)).count();
        if (down == 0) {
            return new ComponentHealth(spec.id(), spec.name(), spec.group(), UP, null, null,
                    reported.size() + "개 서비스에서 정상");
        }
        String status = down == reported.size() ? DOWN : DEGRADED;
        return new ComponentHealth(spec.id(), spec.name(), spec.group(), status, null, null,
                down + "/" + reported.size() + " 서비스에서 DOWN");
    }

    // --- 프로브 --------------------------------------------------------------

    private Mono<ProbeResult> probe(Spec spec, String url) {
        return switch (spec.probe()) {
            case ACTUATOR -> actuator(spec, url, false);
            case ACTUATOR_OR_ROOT -> actuator(spec, url, true);
            case PLAIN -> plain(spec, url);
            case OPENSEARCH -> openSearch(spec, url);
            case SELF, DERIVED_DB, DERIVED_REDIS -> Mono.empty();
        };
    }

    /**
     * health와 info를 <b>동시에</b> 던진다. 둘 다 {@code probeTimeout} 안이므로 컴포넌트 총 소요도
     * 그 안이다. info는 상태 판정에 관여하지 않는다 — 실패하면 버전 열만 빈다.
     */
    private Mono<ProbeResult> actuator(Spec spec, String url, boolean rootFallback) {
        return Mono.defer(() -> Mono.zip(get(url), version(spec, url))
                .flatMap(both -> {
                    HttpProbe response = both.getT1();
                    if (rootFallback && response.status() == 404) {
                        // Actuator가 없는 앱이다. 병렬로 던진 info 응답(역시 404)은 버리고, 다음
                        // 주기부터는 아예 부르지 않는다.
                        actuatorAbsent.add(spec.id());
                        return root(spec, url, response.latencyMs());
                    }
                    actuatorAbsent.remove(spec.id());
                    ProbeResult result = fromActuator(spec, response);
                    String version = both.getT2().orElse(null);
                    return Mono.just(UP.equals(result.status()) && version != null
                            ? result.withVersion(version) : result);
                }));
    }

    /** {@code /actuator/info}의 {@code build.version}. Actuator가 없다고 확인된 대상은 건너뛴다. */
    private Mono<Optional<String>> version(Spec spec, String healthUrl) {
        String infoUrl = infoOf(healthUrl);
        if (infoUrl == null || actuatorAbsent.contains(spec.id())) {
            return Mono.just(Optional.empty());
        }
        return get(infoUrl).map(response -> {
            if (response.error() != null || !isOk(response.status())) {
                return Optional.<String>empty();
            }
            String version = string(map(response.body(), "build"), "version");
            return version == null || version.isBlank() ? Optional.<String>empty() : Optional.of(version);
        });
    }

    /** Actuator가 없으면 서비스 루트가 200인지만 본다 — 살아 있다는 사실 이상은 알 수 없다. */
    private Mono<ProbeResult> root(Spec spec, String healthUrl, long spentMs) {
        String rootUrl = rootOf(healthUrl);
        if (rootUrl == null) {
            return Mono.just(ProbeResult.down(spec, spentMs, "HTTP 404"));
        }
        return get(rootUrl).map(response -> {
            long latency = spentMs + response.latencyMs();
            if (response.error() != null) {
                return ProbeResult.down(spec, latency, response.error());
            }
            return isOk(response.status())
                    ? new ProbeResult(spec.id(), UP, latency, null, "actuator 없음", null, null)
                    : ProbeResult.down(spec, latency, "HTTP " + response.status());
        });
    }

    private Mono<ProbeResult> plain(Spec spec, String url) {
        return get(url).map(response -> {
            if (response.error() != null) {
                return ProbeResult.down(spec, response.latencyMs(), response.error());
            }
            return isOk(response.status())
                    ? new ProbeResult(spec.id(), UP, response.latencyMs(), null, null, null, null)
                    : ProbeResult.down(spec, response.latencyMs(), "HTTP " + response.status());
        });
    }

    private Mono<ProbeResult> openSearch(Spec spec, String url) {
        return get(url).map(response -> {
            long latency = response.latencyMs();
            if (response.error() != null) {
                return ProbeResult.down(spec, latency, response.error());
            }
            if (!isOk(response.status())) {
                return ProbeResult.down(spec, latency, "HTTP " + response.status());
            }
            String clusterStatus = string(response.body(), "status");
            if (clusterStatus == null) {
                return new ProbeResult(spec.id(), UP, latency, null, null, null, null);
            }
            // green·yellow는 서비스 가능. red만 색인 일부를 못 읽는 상태라 DEGRADED로 올린다.
            String status = "red".equalsIgnoreCase(clusterStatus) ? DEGRADED : UP;
            return new ProbeResult(spec.id(), status, latency, null, "cluster " + clusterStatus, null, null);
        });
    }

    private ProbeResult fromActuator(Spec spec, HttpProbe response) {
        long latency = response.latencyMs();
        if (response.error() != null) {
            return ProbeResult.down(spec, latency, response.error());
        }
        Map<String, Object> body = response.body();
        String reported = string(body, "status");
        String db = componentStatus(body, "db");
        String redis = componentStatus(body, "redis");

        if (reported == null) {
            return isOk(response.status())
                    ? new ProbeResult(spec.id(), UP, latency, null, null, db, redis)
                    : ProbeResult.down(spec, latency, "HTTP " + response.status());
        }
        if (UP.equalsIgnoreCase(reported)) {
            return new ProbeResult(spec.id(), UP, latency, null, null, db, redis);
        }
        // UP이 아니면 전부 DOWN(OUT_OF_SERVICE 포함) — 사유에 어떤 하위 컴포넌트가 죽었는지 붙인다.
        return new ProbeResult(spec.id(), DOWN, latency, null, reported + downComponents(body), db, redis);
    }

    // --- HTTP --------------------------------------------------------------

    /**
     * 예외를 던지지 않는 GET. 연결 실패·타임아웃은 {@link HttpProbe#error()}에 짧은 사유로 담기고,
     * JSON이 아닌 본문(text/plain {@code ready}, HTML 오류 페이지)은 빈 맵이 된다 — 상태 코드는 남는다.
     */
    private Mono<HttpProbe> get(String url) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return webClient.get().uri(url)
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        return response.bodyToMono(JSON_MAP)
                                .onErrorResume(e -> Mono.empty())
                                .defaultIfEmpty(Map.of())
                                .map(body -> new HttpProbe(status, body, null, elapsedMs(start)));
                    })
                    .timeout(probeTimeout)
                    .onErrorResume(e -> Mono.just(
                            new HttpProbe(0, Map.of(), reason(e), elapsedMs(start))));
        });
    }

    private String reason(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException) {
                long ms = probeTimeout.toMillis();
                return ms % 1000 == 0 ? "timeout " + (ms / 1000) + "s" : "timeout " + ms + "ms";
            }
            if (t instanceof ConnectException) {
                return "connection refused";
            }
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return "unknown host";
            }
        }
        return e.getClass().getSimpleName();
    }

    private static boolean isOk(int status) {
        return status >= 200 && status < 300;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String string(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        return body.get(key) instanceof String value ? value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        return body.get(key) instanceof Map<?, ?> value ? (Map<String, Object>) value : null;
    }

    private static String componentStatus(Map<String, Object> body, String component) {
        return string(map(map(body, "components"), component), "status");
    }

    /** DOWN 사유에 붙일 하위 컴포넌트 이름. 길어지지 않게 앞 세 개만. */
    private static String downComponents(Map<String, Object> body) {
        Map<String, Object> components = map(body, "components");
        if (components == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Object> entry : components.entrySet()) {
            if (names.size() >= 3) {
                break;
            }
            if (entry.getValue() instanceof Map<?, ?> component
                    && component.get("status") instanceof String status
                    && !UP.equalsIgnoreCase(status)) {
                names.add(entry.getKey());
            }
        }
        return names.isEmpty() ? "" : " (" + String.join(", ", names) + ")";
    }

    /** {@code …/actuator/health} → {@code …/actuator/info}. 그 형태가 아니면 버전을 묻지 않는다. */
    private static String infoOf(String healthUrl) {
        if (healthUrl == null || !healthUrl.endsWith("/actuator/health")) {
            return null;
        }
        return healthUrl.substring(0, healthUrl.length() - "health".length()) + "info";
    }

    /** {@code http://host:port/…} → {@code http://host:port/}. */
    private static String rootOf(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url + "/" : url.substring(0, pathStart) + "/";
    }

    // --- 내부 값 -------------------------------------------------------------

    /** 한 번의 HTTP 시도 결과. {@code error != null}이면 응답 자체가 없었다는 뜻이다. */
    private record HttpProbe(int status, Map<String, Object> body, String error, long latencyMs) {}

    /**
     * 컴포넌트 하나의 프로브 결과.
     *
     * @param dbStatus    Actuator {@code components.db.status}(없으면 null) — postgres 행 파생에 쓴다
     * @param redisStatus Actuator {@code components.redis.status}(없으면 null) — redis 행 파생에 쓴다
     */
    record ProbeResult(String id, String status, Long latencyMs, String version, String detail,
                       String dbStatus, String redisStatus) {

        static ProbeResult down(Spec spec, long latencyMs, String detail) {
            return new ProbeResult(spec.id(), DOWN, latencyMs, null, detail, null, null);
        }

        ProbeResult withVersion(String newVersion) {
            return new ProbeResult(id, status, latencyMs, newVersion, detail, dbStatus, redisStatus);
        }
    }
}
