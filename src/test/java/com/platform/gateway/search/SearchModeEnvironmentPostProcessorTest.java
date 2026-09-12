package com.platform.gateway.search;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SEARCH_MODE} → Environment 파생. 라우트 대상이 여기서 정해지므로, 틀리면 검색이 조용히
 * 통째로 죽는다(사후 검출이 아니라 여기서 못박는다).
 */
class SearchModeEnvironmentPostProcessorTest {

    private final List<String> warnings = new ArrayList<>();

    private StandardEnvironment environment(String... keyValues) {
        Map<String, Object> source = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            source.put(keyValues[i], keyValues[i + 1]);
        }
        StandardEnvironment environment = new StandardEnvironment();
        // 실행한 사람의 셸에 SEARCH_MODE가 있든 없든 결과가 같아야 한다.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test", source));
        return environment;
    }

    /** 실제 기동에서는 로깅 시스템 초기화 전이라 DeferredLog를 받는다 — 여기서는 경고만 주워 담는다. */
    private SearchModeEnvironmentPostProcessor processor() {
        Log log = (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[]{Log.class},
                (proxy, method, args) -> {
                    if ("warn".equals(method.getName()) && args != null && args.length > 0) {
                        warnings.add(String.valueOf(args[0]));
                    }
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
        DeferredLogFactory factory = supplier -> log;
        return new SearchModeEnvironmentPostProcessor(factory);
    }

    private StandardEnvironment derive(String... keyValues) {
        StandardEnvironment environment = environment(keyValues);
        processor().postProcessEnvironment(environment, null);
        return environment;
    }

    private String routeUri(String... keyValues) {
        return derive(keyValues).getProperty(SearchModeEnvironmentPostProcessor.ROUTE_URI_PROPERTY);
    }

    private String mode(String... keyValues) {
        return derive(keyValues).getProperty(SearchModeEnvironmentPostProcessor.MODE_PROPERTY);
    }

    // --- 모드별 라우트 대상 ------------------------------------------------------

    @Test
    void 설정이_없으면_lite로_위키를_본다() {
        assertThat(mode()).isEqualTo("lite");
        assertThat(routeUri()).isEqualTo("lb://wiki-backend");
    }

    @Test
    void opensearch는_search_service로_간다() {
        assertThat(mode("SEARCH_MODE", "opensearch")).isEqualTo("opensearch");
        assertThat(routeUri("SEARCH_MODE", "opensearch")).isEqualTo("http://search-service:9140");
    }

    @Test
    void external도_search_service로_간다() {
        assertThat(routeUri("SEARCH_MODE", "external")).isEqualTo("http://search-service:9140");
    }

    @Test
    void 대소문자와_공백을_무시한다() {
        assertThat(mode("SEARCH_MODE", " OpenSearch ")).isEqualTo("opensearch");
        assertThat(warnings).isEmpty();
    }

    /** docker는 WIKI_SERVICE_URI를 비워 두지만(lb:// N인스턴스), 명시한 배포는 그 주소로 간다. */
    @Test
    void lite에서_WIKI_SERVICE_URI가_있으면_그것을_쓴다() {
        assertThat(routeUri("SEARCH_MODE", "lite", "WIKI_SERVICE_URI", "http://wiki-backend:9110"))
                .isEqualTo("http://wiki-backend:9110");
    }

    /** 탈출구. compose가 이 값을 기본으로 채워두면 SEARCH_MODE가 무력화된다 — 비워 두어야 한다. */
    @Test
    void SEARCH_SERVICE_URI가_명시되면_모드를_이긴다() {
        assertThat(routeUri("SEARCH_MODE", "lite", "SEARCH_SERVICE_URI", "http://search.internal:9200"))
                .isEqualTo("http://search.internal:9200");
        assertThat(routeUri("SEARCH_MODE", "opensearch", "SEARCH_SERVICE_URI", "http://legacy:9140"))
                .isEqualTo("http://legacy:9140");
    }

    // --- 잘못된 값 -------------------------------------------------------------

    /** 오타 하나로 단일 진입점 전체가 안 뜨는 편이 검색만 라이트로 도는 것보다 나쁘다. */
    @Test
    void 모르는_값은_경고하고_lite로_간다() {
        assertThat(mode("SEARCH_MODE", "elasticsearch")).isEqualTo("lite");
        assertThat(routeUri("SEARCH_MODE", "elasticsearch")).isEqualTo("lb://wiki-backend");
        assertThat(warnings).anyMatch(w -> w.contains("elasticsearch") && w.contains("lite"));
    }

    @Test
    void 빈_값은_경고_없이_lite다() {
        assertThat(mode("SEARCH_MODE", "   ")).isEqualTo("lite");
        assertThat(warnings).isEmpty();
    }

    // --- 우선순위·멱등 ---------------------------------------------------------

    /** yml·테스트가 platform.search.mode로 줄 수도 있다. SEARCH_MODE가 있으면 그쪽이 먼저다. */
    @Test
    void SEARCH_MODE가_platform_search_mode보다_우선한다() {
        assertThat(mode("platform.search.mode", "opensearch")).isEqualTo("opensearch");
        assertThat(mode("SEARCH_MODE", "lite", "platform.search.mode", "opensearch")).isEqualTo("lite");
    }

    /** 두 번 돌아도 자기가 심은 값을 입력으로 다시 읽지 않는다. */
    @Test
    void 두_번_돌려도_결과가_같다() {
        StandardEnvironment environment = environment("SEARCH_MODE", "external");
        processor().postProcessEnvironment(environment, null);
        processor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(SearchModeEnvironmentPostProcessor.MODE_PROPERTY))
                .isEqualTo("external");
        assertThat(environment.getProperty(SearchModeEnvironmentPostProcessor.ROUTE_URI_PROPERTY))
                .isEqualTo("http://search-service:9140");
    }

    /** 파생값은 최우선 소스여야 한다 — application.yml이 덮어쓰면 스위치가 둘이 된다. */
    @Test
    void 파생값은_다른_소스를_덮는다() {
        StandardEnvironment environment = environment(
                "SEARCH_MODE", "opensearch",
                SearchModeEnvironmentPostProcessor.ROUTE_URI_PROPERTY, "http://someone-else:1");
        processor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(SearchModeEnvironmentPostProcessor.ROUTE_URI_PROPERTY))
                .isEqualTo("http://search-service:9140");
    }
}
