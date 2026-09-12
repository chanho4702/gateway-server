package com.platform.gateway;

import com.platform.gateway.search.SearchProperties;
import com.platform.gateway.search.SearchMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SEARCH_MODE=opensearch}로 뜬 애플리케이션. 여기서 보는 것은 <b>배선</b>이다 — 파생 규칙 자체는
 * {@code SearchModeTest}·{@code SearchModeEnvironmentPostProcessorTest}가 이미 표로 검증하므로,
 * 이 스위트는 그 결과가 실제 라우트와 빈까지 도달하는지만 확인한다(EnvironmentPostProcessor 등록
 * 누락이면 여기서 죽는다). 기본값(lite) 쪽은 {@code RouteConfigTest}가 본다.
 */
@SpringBootTest(properties = "SEARCH_MODE=opensearch")
class SearchModeRouteTest {

    @Autowired
    RouteLocator routeLocator;

    @Autowired
    SearchProperties searchProperties;

    private Route searchRoute() {
        return routeLocator.getRoutes().collectList().block().stream()
                .filter(r -> r.getId().equals("search")).findFirst().orElseThrow();
    }

    @Test
    void opensearch_모드는_search_service로_라우팅한다() {
        assertThat(searchRoute().getUri())
                .hasScheme("http").hasHost("search-service").hasPort(9140);
    }

    /** 모드가 바뀌어도 경로 매핑과 보호는 그대로다 — 대상만 갈아끼우는 스위치다. */
    @Test
    void 모드가_바뀌어도_StripPrefix와_레이트리밋은_남는다() {
        String filters = searchRoute().getFilters().toString();
        assertThat(filters).contains("StripPrefix parts = 2");
        assertThat(filters).contains("RequestRateLimiter");
    }

    /** 헬스 집계·기능 플래그 API가 읽는 빈에도 같은 값이 도달해야 한다(스위치는 하나다). */
    @Test
    void 파생된_모드가_빈에도_도달한다() {
        assertThat(searchProperties.getMode()).isEqualTo(SearchMode.OPENSEARCH);
        assertThat(searchProperties.getRouteUri()).isEqualTo("http://search-service:9140");
    }
}
