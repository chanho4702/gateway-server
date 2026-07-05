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
                "board", "auth-oauth2", "auth-login", "auth-api", "auth-jwks", "auth-me");
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
}
