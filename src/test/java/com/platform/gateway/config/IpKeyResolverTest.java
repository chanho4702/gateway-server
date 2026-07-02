package com.platform.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class IpKeyResolverTest {

    private final KeyResolver resolver = new RateLimitConfig().ipKeyResolver();

    @Test
    void resolvesClientIp() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/refresh")
                        .remoteAddress(new InetSocketAddress("10.1.2.3", 51234)).build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("10.1.2.3");
    }

    @Test
    void fallsBackToUnknownWhenNoRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/refresh").build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("unknown");
    }
}
