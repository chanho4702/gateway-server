package com.platform.gateway.config;

import com.platform.gateway.filter.PatExchangeRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;

import static org.assertj.core.api.Assertions.assertThat;

/** PAT 교환 속도 제한이 라우트들과 같은 RedisRateLimiter 빈에 별도 라우트 id로 얹히는지(P-08). */
@SpringBootTest
class PatExchangeRateLimitConfigTest {

    @Autowired RedisRateLimiter redisRateLimiter;
    @Autowired PatExchangeRateLimiter patExchangeRateLimiter;

    @Test
    void registersPatExchangeBucketOnSharedRedisRateLimiter() {
        RedisRateLimiter.Config config = redisRateLimiter.getConfig().get(PatExchangeRateLimitConfig.ROUTE_ID);
        assertThat(config).isNotNull();
        assertThat(config.getReplenishRate()).isEqualTo(10);
        assertThat(config.getBurstCapacity()).isEqualTo(20);
        assertThat(patExchangeRateLimiter).isNotSameAs(PatExchangeRateLimiter.UNLIMITED);
    }
}
