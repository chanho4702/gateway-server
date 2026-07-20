package com.platform.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class RateLimitConfig {

    // nginx 1홉 뒤 — X-Forwarded-For의 맨 오른쪽(nginx가 append한 실 클라이언트 IP)만 신뢰.
    // getRemoteAddress()를 그대로 쓰면 nginx/도커 NAT IP 하나로 고정돼 전 클라이언트가
    // 단일 rate-limit 버킷을 공유한다(정상 사용자 상호 차단 + 브루트포스 격리 불가).
    // trusted-proxies(application.yml)가 XFF를 온전히 통과시키므로 여기서 실 IP를 뽑는다.
    // 클라이언트가 XFF를 위조해도 신뢰 홉이 1개라 nginx가 붙인 값만 채택 → 위조 무력.
    private final XForwardedRemoteAddressResolver clientIpResolver =
            XForwardedRemoteAddressResolver.maxTrustedIndex(1);

    /** 실 클라이언트 IP 기준 rate limit 키. XFF 부재 시(직결 스모크 등) remoteAddress로 폴백. */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            InetSocketAddress addr = clientIpResolver.resolve(exchange);
            String ip = (addr != null && addr.getAddress() != null)
                    ? addr.getAddress().getHostAddress()
                    : "unknown";
            return Mono.just(ip);
        };
    }
}
