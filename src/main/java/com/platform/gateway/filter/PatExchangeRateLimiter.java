package com.platform.gateway.filter;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * PAT 교환(auth-server 호출) 직전의 요청 속도 제한. 캐시 미스일 때만 묻는다 — 캐시에 든 정상 토큰의
 * 트래픽은 auth-server에 부하를 싣지 않으므로 제한할 이유가 없다(P-08).
 *
 * <p>필터와 분리한 이유는 시험이다: 필터 단위 테스트는 Redis 없이 "허용/거부"만 갈아끼운다.
 */
@FunctionalInterface
public interface PatExchangeRateLimiter {

    /** 제한 없음 — 테스트·Redis 리미터 빈이 없는 구성용. */
    PatExchangeRateLimiter UNLIMITED = exchange -> Mono.just(true);

    /** 이 요청이 교환을 시도해도 되는지. false면 필터가 429를 낸다. */
    Mono<Boolean> tryAcquire(ServerWebExchange exchange);
}
