package com.platform.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/** CORS 단일 소스의 값 — 허용 메서드에 PATCH, 오리진은 쉼표 목록(P-16). */
class CorsOriginsTest {

    private static CorsConfiguration configFor(String origins) {
        return new SecurityConfig().corsConfigurationSource(origins)
                .getCorsConfiguration(MockServerWebExchange.from(MockServerHttpRequest.get("/api/org/members")));
    }

    @Test
    void allowsPatch() {
        assertThat(configFor("http://localhost:5173").getAllowedMethods()).contains("PATCH");
    }

    @Test
    void acceptsCommaSeparatedOriginsAndTrims() {
        assertThat(configFor("http://localhost:5173, http://localhost:5174,,").getAllowedOrigins())
                .containsExactly("http://localhost:5173", "http://localhost:5174");
    }

    @Test
    void singleOriginStillWorks() {
        assertThat(configFor("http://localhost:5173").getAllowedOrigins()).containsExactly("http://localhost:5173");
    }
}
