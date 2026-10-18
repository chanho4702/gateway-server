package com.platform.gateway.config;

import com.platform.gateway.filter.PatExchangeRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PAT 교환 경로의 IP당 속도 제한(P-08) — 라우트들이 쓰는 <b>같은</b> {@link RedisRateLimiter} 빈과
 * {@code ipKeyResolver}(XFF 실 IP)를 재사용한다. 가상 라우트 id {@value #ROUTE_ID}에 설정을 하나 얹을 뿐이라
 * Redis 키도 라우트별로 갈라진다(다른 라우트의 버킷과 섞이지 않는다).
 *
 * <p>Redis 부재 시 fail-open(통과)은 다른 라우트의 RequestRateLimiter와 같다 — 이 제한은 증폭 방어이지
 * 인증이 아니다. 인증 판단(교환 결과 캐시)은 여전히 인스턴스 로컬 Caffeine이다.
 */
@Configuration
public class PatExchangeRateLimitConfig {

    static final String ROUTE_ID = "pat-exchange";
    private static final Logger log = LoggerFactory.getLogger(PatExchangeRateLimitConfig.class);

    @Bean
    PatExchangeRateLimiter patExchangeRateLimiter(
            ObjectProvider<RedisRateLimiter> redisRateLimiter,
            @Qualifier("ipKeyResolver") KeyResolver ipKeyResolver,
            @Value("${platform.pat-exchange.rate-limit.replenish-rate:10}") int replenishRate,
            @Value("${platform.pat-exchange.rate-limit.burst-capacity:20}") int burstCapacity) {
        RedisRateLimiter limiter = redisRateLimiter.getIfAvailable();
        if (limiter == null) {
            log.warn("RedisRateLimiter 빈이 없어 PAT 교환 속도 제한이 꺼집니다");
            return PatExchangeRateLimiter.UNLIMITED;
        }
        limiter.getConfig().put(ROUTE_ID, new RedisRateLimiter.Config()
                .setReplenishRate(replenishRate)
                .setBurstCapacity(burstCapacity));
        return exchange -> ipKeyResolver.resolve(exchange)
                .defaultIfEmpty("unknown")
                .flatMap(ip -> limiter.isAllowed(ROUTE_ID, ip))
                .map(RateLimiter.Response::isAllowed);
    }
}
