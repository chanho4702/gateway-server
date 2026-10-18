package com.platform.gateway.config;

import com.platform.gateway.security.AcceptAnyJwtConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Import;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trusted-proxies가 실제로 X-Forwarded-*의 통과 여부를 가르는지(P-23). 테스트 클라이언트는 루프백(127.0.0.1)에서
 * 붙는다 — base에서는 신뢰(헤더가 다운스트림까지), docker에서는 불신(헤더 제거)이어야 한다.
 * 그리고 신뢰 범위와 무관하게 rate limit 키(XFF 맨 오른쪽 1홉)는 그대로여야 한다.
 */
class TrustedProxiesForwardingTest {

    static ForwardedHostStub stub;

    static {
        try {
            stub = new ForwardedHostStub();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        stub.server.stop(0);
    }

    @DynamicPropertySource
    static void auth(DynamicPropertyRegistry registry) {
        registry.add("AUTH_SERVER_URI", stub::uri);
    }

    static void callWithForwardedHost(int port) {
        stub.forwardedHost.set(null);
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build()
                .get().uri("/api/me")
                .header("Authorization", "Bearer any")
                .header("X-Forwarded-Host", "evil.example")
                .exchange()
                .expectStatus().isOk();
    }

    static String resolveKey(KeyResolver resolver) {
        return resolver.resolve(MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/refresh")
                .header("X-Forwarded-For", "1.2.3.4, 203.0.113.9")
                .remoteAddress(new InetSocketAddress("172.22.0.26", 40000)).build())).block();
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(AcceptAnyJwtConfig.class)
    class BaseProfile {
        @LocalServerPort int port;
        @Autowired KeyResolver ipKeyResolver;

        @Test
        void loopbackProxyIsTrusted() {
            callWithForwardedHost(port);
            assertThat(stub.forwardedHost.get()).contains("evil.example");
        }

        @Test
        void rateLimitKeyIsRightmostXffHop() {
            assertThat(resolveKey(ipKeyResolver)).isEqualTo("203.0.113.9");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("docker")
    @Import(AcceptAnyJwtConfig.class)
    class DockerProfile {
        @LocalServerPort int port;
        @Autowired KeyResolver ipKeyResolver;

        @Test
        void loopbackIsNotTrustedSoClientForwardedHostIsDropped() {
            callWithForwardedHost(port);
            assertThat(stub.forwardedHost.get()).doesNotContain("evil.example");
        }

        @Test
        void rateLimitKeyIsStillRightmostXffHop() {
            assertThat(resolveKey(ipKeyResolver)).isEqualTo("203.0.113.9");
        }
    }
}
