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
