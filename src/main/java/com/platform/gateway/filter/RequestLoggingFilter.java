package com.platform.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/** 모든 요청에 X-Request-Id를 보장(없거나 형식이 안전하지 않으면 재발급)해 다운스트림으로 전파하고, 요청을 1줄 로깅한다. */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    static final String REQUEST_ID = "X-Request-Id";
    // 클라이언트 입력을 로그·다운스트림 헤더에 싣기 전 허용 문자/길이 검증(로그 인젝션 방지).
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID);
        if (requestId == null || !SAFE_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        final String id = requestId;
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(REQUEST_ID, id)))
                .build();
        log.info("{} {} ({})",
                mutated.getRequest().getMethod(),
                mutated.getRequest().getURI().getPath(),
                id);
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
