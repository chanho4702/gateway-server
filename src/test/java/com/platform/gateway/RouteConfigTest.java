package com.platform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RouteConfigTest {

    @Autowired
    RouteLocator routeLocator;

    @Test
    void allExpectedRoutesAreConfigured() {
        List<String> ids = routeLocator.getRoutes()
                .map(r -> r.getId())
                .collectList()
                .block();
        assertThat(ids).contains(
                "board", "auth-oauth2", "auth-login", "auth-api", "auth-jwks", "auth-me",
                "org", "wiki-collaboration-bootstrap", "wiki-collaboration", "wiki", "alm", "search", "agent");
    }

    @Test
    void routesResolveViaServiceDiscoveryByDefault() {
        // env 미주입 시 기본값이 lb:// — 유레카 레지스트리에서 인스턴스를 찾는다.
        // (BOARD_SERVICE_URI/AUTH_SERVER_URI env는 테스트 스텁 주입·유레카 없는 환경용 시임으로 유지)
        List<org.springframework.cloud.gateway.route.Route> routes =
                routeLocator.getRoutes().collectList().block();
        var board = routes.stream().filter(r -> r.getId().equals("board")).findFirst().orElseThrow();
        assertThat(board.getUri()).hasScheme("lb").hasHost("board-service");
        for (String id : List.of("auth-oauth2", "auth-login", "auth-api", "auth-jwks", "auth-me")) {
            var route = routes.stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
            assertThat(route.getUri()).as("route %s", id).hasScheme("lb").hasHost("auth-server");
        }
    }

    @Test
    void authRoutesHaveRateLimiterFilter() {
        List<org.springframework.cloud.gateway.route.Route> routes =
                routeLocator.getRoutes().collectList().block();
        for (String id : List.of("auth-api", "auth-oauth2", "auth-login")) {
            var route = routes.stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
            assertThat(route.getFilters().toString())
                    .as("route %s에 RequestRateLimiter 필터", id)
                    .contains("RequestRateLimiter");
        }
    }

    /**
     * 제품 서비스 3개도 기본값이 lb:// 여야 한다 — docker 프로필만 *_SERVICE_URI(DNS 직결)로
     * 갈아끼우고 dev/기본은 Eureka를 탄다. 기본값이 굳어버리면 dev 오프셋 클러스터가
     * 도커 컨테이너 이름을 찾다 UnknownHostException으로 죽는다.
     */
    @Test
    void productRoutesResolveViaServiceDiscoveryByDefault() {
        var routes = routeLocator.getRoutes().collectList().block();
        record Expect(String id, String host) {}
        for (Expect e : List.of(new Expect("org", "org-service"),
                new Expect("wiki", "wiki-backend"),
                new Expect("alm", "alm-backend"),
                new Expect("search", "search-service"),
                new Expect("agent", "agent-service"))) {
            var route = routes.stream().filter(r -> r.getId().equals(e.id())).findFirst().orElseThrow();
            assertThat(route.getUri()).as("route %s", e.id()).hasScheme("lb").hasHost(e.host());
        }
    }

    @Test
    void collaborationWebSocketRoutePrecedesWikiRestCatchAll() {
        var routes = routeLocator.getRoutes().collectList().block();
        var bootstrap = routes.stream()
                .filter(r -> r.getId().equals("wiki-collaboration-bootstrap"))
                .findFirst().orElseThrow();
        var collaboration = routes.stream()
                .filter(r -> r.getId().equals("wiki-collaboration")).findFirst().orElseThrow();
        var wiki = routes.stream().filter(r -> r.getId().equals("wiki")).findFirst().orElseThrow();

        assertThat(collaboration.getUri())
                .hasScheme("ws")
                .hasHost("localhost")
                .hasPort(19150);
        assertThat(collaboration.getOrder()).isLessThan(wiki.getOrder());
        assertThat(bootstrap.getUri())
                .hasScheme("http")
                .hasHost("localhost")
                .hasPort(19150);
        assertThat(bootstrap.getOrder()).isLessThan(collaboration.getOrder());
        assertThat(bootstrap.getPredicate().toString())
                .contains("/api/wiki/collaboration/pages/*/bootstrap");
    }

    /**
     * search 라우트만 StripPrefix=2를 갖는다(설계 §8). search-service의 컨트롤러 경로는
     * /graphql·/admin/reindex이고 /api/search 접두사를 갖지 않으므로, 이 필터가 빠지면
     * 외부 /api/search/graphql이 서비스의 존재하지 않는 /api/search/graphql로 그대로 넘어가 404가 된다.
     * org/wiki는 반대로 접두사를 갖고 있어 붙이면 안 된다.
     */
    @Test
    void searchRouteStripsApiSearchPrefix() {
        var routes = routeLocator.getRoutes().collectList().block();
        var search = routes.stream().filter(r -> r.getId().equals("search")).findFirst().orElseThrow();
        assertThat(search.getFilters().toString())
                .as("search 라우트의 StripPrefix parts=2")
                .contains("StripPrefix parts = 2");

        for (String id : List.of("org", "wiki", "alm", "agent")) {
            var route = routes.stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
            assertThat(route.getFilters().toString())
                    .as("route %s는 접두사를 떼지 않는다", id)
                    .doesNotContain("StripPrefix");
        }
    }

    /** 검색은 한 요청이 색인 전체를 훑을 수 있어 비용이 크다 — rate limiter 보호가 필수(설계 §8). */
    @Test
    void searchRouteHasRateLimiterFilter() {
        var routes = routeLocator.getRoutes().collectList().block();
        var search = routes.stream().filter(r -> r.getId().equals("search")).findFirst().orElseThrow();
        assertThat(search.getFilters().toString()).contains("RequestRateLimiter");
    }
}
