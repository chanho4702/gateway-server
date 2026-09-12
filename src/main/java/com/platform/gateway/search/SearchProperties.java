package com.platform.gateway.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code platform.search.*} — {@link SearchModeEnvironmentPostProcessor}가 {@code SEARCH_MODE}에서
 * 파생해 심어 둔 값을 빈으로 읽는다. 사람이 이 두 키를 직접 설정하는 것이 아니다(스위치는 하나다).
 *
 * <p>읽는 곳은 둘이다 — 헬스 집계({@code HealthAggregator.isProbed})와 기능 플래그 API
 * ({@code GET /api/platform/features}). 라우트 대상은 yml이 {@code platform.search.route-uri}
 * 플레이스홀더로 직접 쓴다.
 */
@Component
@ConfigurationProperties(prefix = "platform.search")
public class SearchProperties {

    /** 정규화된 모드. EnvironmentPostProcessor가 항상 채우므로 이 기본값이 쓰일 일은 없다. */
    private SearchMode mode = SearchMode.DEFAULT;

    /** 파생된 {@code /api/search/**} 라우트 대상. 진단·로그용(라우팅 자체는 yml이 한다). */
    private String routeUri;

    public SearchMode getMode() {
        return mode;
    }

    public void setMode(SearchMode mode) {
        this.mode = mode == null ? SearchMode.DEFAULT : mode;
    }

    public String getRouteUri() {
        return routeUri;
    }

    public void setRouteUri(String routeUri) {
        this.routeUri = routeUri;
    }
}
