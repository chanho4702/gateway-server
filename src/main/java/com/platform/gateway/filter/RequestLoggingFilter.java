package com.platform.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 모든 요청에 X-Request-Id를 보장(없거나 형식이 안전하지 않으면 재발급)해 다운스트림으로 전파하고,
 * 진입 시 1줄 + 완료 시 status·latency 1줄을 로깅한다(게이트웨이가 유일 진입점 = access log 주인).
 * addKeyValue로 남긴 필드는 docker 프로필의 ECS JSON 출력에서 Loki 질의 가능한 필드가 된다.
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {
    //분산 추적의 시작점
    //GlobalFilter라 모든 라우트에 적용되고, HIGHEST_PRECEDENCE라 제일 먼저 실행

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    static final String REQUEST_ID = "X-Request-Id";
    // 클라이언트 입력을 로그·다운스트림 헤더에 싣기 전 허용 문자/길이 검증(로그 인젝션 방지).
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /*
    *   1. 클라이언트가 보낸 X-Request-Id가 있고 형식이 안전하면 유지, 아니면 UUID 재발급.
        2. 헤더에 set(덮어쓰기)으로 넣고 다운스트림에 전파 — 이 ID 하나로 게이트웨이→auth→board 로그를 꿰어 추적할 수 있게.
        3. 진입 1줄 로깅 + 완료(doFinally) 시 status·latency 1줄 로깅 — 취소/에러 시에도 완료 라인이 남는다.
    * */
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
        final String method = mutated.getRequest().getMethod().name();
        final String path = mutated.getRequest().getURI().getPath();
        final long startNanos = System.nanoTime();

        log.atInfo()
                .addKeyValue("event", "request.in")
                .addKeyValue("method", method)
                .addKeyValue("path", path)
                .addKeyValue("requestId", id)
                .log("{} {} ({})", method, path, id);

        return chain.filter(mutated).doFinally(signal -> {
            HttpStatusCode status = mutated.getResponse().getStatusCode();
            Integer statusCode = status != null ? status.value() : null;
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.atInfo()
                    .addKeyValue("event", "request.out")
                    .addKeyValue("method", method)
                    .addKeyValue("path", path)
                    .addKeyValue("status", statusCode)
                    .addKeyValue("latencyMs", latencyMs)
                    .addKeyValue("requestId", id)
                    .log("{} {} -> {} {}ms ({})", method, path,
                            statusCode != null ? statusCode : "?", latencyMs, id);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
