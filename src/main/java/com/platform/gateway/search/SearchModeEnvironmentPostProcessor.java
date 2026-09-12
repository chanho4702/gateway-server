package com.platform.gateway.search;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code SEARCH_MODE} 하나에서 라우트 대상과 정규화된 모드를 파생해 Environment에 심는다(설계 §2.1 A안).
 *
 * <p>왜 {@code @ConfigurationProperties}가 아니라 EnvironmentPostProcessor인가: 라우트 정의
 * ({@code spring.cloud.gateway.server.webflux.routes[].uri})는 <b>플레이스홀더가 풀린 문자열</b>로
 * 바인딩된다. 조건 분기를 yml에 쓸 방법이 없으므로, 바인딩보다 먼저 값을 계산해
 * {@code platform.search.route-uri}로 넣어두고 yml은 그것만 참조한다.
 *
 * <p>파생 규칙은 {@link SearchMode#routeUri(String, String)}에 있다. 모르는 값은
 * <b>기동 실패가 아니라 경고 + {@code lite}</b>다 — 오타 하나로 단일 진입점 전체가 안 뜨는 편이
 * 검색만 라이트로 도는 것보다 나쁘다.
 *
 * <p>순서는 {@link ConfigDataEnvironmentPostProcessor} 뒤다. 그래야 application.yml에 적힌
 * {@code platform.search.mode}(테스트·특수 배포용)도 읽을 수 있다.
 */
public class SearchModeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 이 프로세서가 심는 프로퍼티 소스 이름. 최우선(addFirst)이라 다른 소스가 덮지 못한다. */
    static final String PROPERTY_SOURCE_NAME = "platform-search-mode";

    /** 정규화된 모드({@code lite}·{@code opensearch}·{@code external}). */
    public static final String MODE_PROPERTY = "platform.search.mode";

    /** {@code /api/search/**} 라우트 대상. application.yml이 이 값을 그대로 쓴다. */
    public static final String ROUTE_URI_PROPERTY = "platform.search.route-uri";

    private static final String MODE_ENV = "SEARCH_MODE";
    private static final String SEARCH_SERVICE_URI_ENV = "SEARCH_SERVICE_URI";
    private static final String WIKI_SERVICE_URI_ENV = "WIKI_SERVICE_URI";

    private final Log log;

    public SearchModeEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        // 이 시점엔 로깅 시스템이 아직 없다 — DeferredLog가 초기화 후 그대로 재생한다.
        this.log = logFactory.getLog(getClass());
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 두 번 돌아도 자기가 심은 값을 입력으로 읽지 않게 먼저 걷어낸다.
        environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);

        String raw = firstNonBlank(environment.getProperty(MODE_ENV), environment.getProperty(MODE_PROPERTY));
        SearchMode mode = SearchMode.parse(raw);
        if (mode == null) {
            if (raw != null && !raw.isBlank()) {
                log.warn("SEARCH_MODE='" + raw + "'는 모르는 값이다 — lite로 간주한다"
                        + "(lite|opensearch|external).");
            }
            mode = SearchMode.DEFAULT;
        }

        String routeUri = mode.routeUri(environment.getProperty(SEARCH_SERVICE_URI_ENV),
                environment.getProperty(WIKI_SERVICE_URI_ENV));

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put(MODE_PROPERTY, mode.id());
        derived.put(ROUTE_URI_PROPERTY, routeUri);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, derived));

        // 라우트가 어디로 가는지는 장애 조사에서 가장 먼저 확인하는 값이다 — 기동 로그에 남긴다.
        log.info("검색 모드 " + mode.id() + " — /api/search/** → " + routeUri);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
