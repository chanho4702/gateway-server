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
}
