package com.platform.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {

    @Test
    void generatesRequestIdWhenAbsent() {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/board/posts").build());

        // chain: 전달된 exchange에서 X-Request-Id 헤더가 채워졌는지 캡처
        filter.filter(exchange, new GatewayFilterChain() {
            @Override
            public Mono<Void> filter(ServerWebExchange ex) {
                String id = ex.getRequest().getHeaders().getFirst("X-Request-Id");
                assertThat(id).isNotBlank();
                return Mono.empty();
            }
        }).block();
    }

    @Test
    void regeneratesRequestIdWhenFormatIsUnsafe() {
        // 클라이언트가 준 값에 특수문자가 섞이면(로그 인젝션/헤더 오염) 신뢰하지 않고 재발급한다.
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/board/posts")
                        .header("X-Request-Id", "bad id;\"><script>").build());

        filter.filter(exchange, new GatewayFilterChain() {
            @Override
            public Mono<Void> filter(ServerWebExchange ex) {
                String id = ex.getRequest().getHeaders().getFirst("X-Request-Id");
                assertThat(id).isNotBlank().matches("[A-Za-z0-9._-]{1,64}");
                return Mono.empty();
            }
        }).block();
    }

    @Test
    void regeneratesRequestIdWhenTooLong() {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/board/posts")
                        .header("X-Request-Id", "a".repeat(65)).build());

        filter.filter(exchange, new GatewayFilterChain() {
            @Override
            public Mono<Void> filter(ServerWebExchange ex) {
                String id = ex.getRequest().getHeaders().getFirst("X-Request-Id");
                assertThat(id).isNotBlank().hasSizeLessThanOrEqualTo(64);
                return Mono.empty();
            }
        }).block();
    }

    @Test
    void preservesExistingRequestId() {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/board/posts")
                        .header("X-Request-Id", "fixed-123").build());

        filter.filter(exchange, new GatewayFilterChain() {
            @Override
            public Mono<Void> filter(ServerWebExchange ex) {
                assertThat(ex.getRequest().getHeaders().getFirst("X-Request-Id")).isEqualTo("fixed-123");
                assertThat(ex.getRequest().getHeaders().get("X-Request-Id")).hasSize(1);
                return Mono.empty();
            }
        }).block();
    }
}
