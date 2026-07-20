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

    @Test
    void usesXForwardedForBehindProxy() {
        // nginx 1홉 뒤 — remoteAddress는 도커 NAT IP(nginx)지만 rate-limit 키는 실 클라이언트 IP여야 한다.
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/refresh")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .remoteAddress(new InetSocketAddress("172.18.0.5", 40000)).build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("203.0.113.9");
    }

    @Test
    void ignoresSpoofedXForwardedForBeyondTrustedHop() {
        // 클라이언트가 XFF를 위조해도(맨 앞) nginx가 append한 실 IP(맨 뒤, 신뢰 홉 1개)만 채택한다.
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/refresh")
                        .header("X-Forwarded-For", "1.2.3.4, 203.0.113.9")
                        .remoteAddress(new InetSocketAddress("172.18.0.5", 40000)).build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("203.0.113.9");
    }
}
