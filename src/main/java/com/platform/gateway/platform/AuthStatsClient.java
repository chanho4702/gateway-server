package com.platform.gateway.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * auth-server {@code GET /internal/pat/stats} 프록시 — 개인 API 토큰 현황({@code activeTokens},
 * {@code usersWithTokens}, {@code expiringWithin7Days}).
 *
 * <p>{@code PatExchangeClient}와 같은 base URI·공유 시크릿({@code X-Internal-Secret})을 쓴다. 별도
 * 클래스로 둔 이유는 교환 경로(요청마다 도는 인증 핵심)를 관리 화면 통계 때문에 건드리지 않기 위해서다.
 *
 * <p>결과는 60초 캐시. 실패는 5초만 캐시한다 — 잠깐의 장애가 화면을 1분 동안 붙잡아두지 않게.
 */
@Component
public class AuthStatsClient {

    private static final Logger log = LoggerFactory.getLogger(AuthStatsClient.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final Duration FAILURE_TTL = Duration.ofSeconds(5);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final String internalSecret;
    private final Duration timeout;
    private final Mono<Map<String, Object>> cached;

    @Autowired
    AuthStatsClient(@Value("${platform.auth-server-base-uri}") String authServerBaseUri,
                    @Value("${platform.internal-secret:}") String internalSecret) {
        this(WebClient.builder(), authServerBaseUri, internalSecret, DEFAULT_TIMEOUT, CACHE_TTL);
    }

    /** 테스트용 — MockWebServer 주소와 짧은 TTL. */
    AuthStatsClient(WebClient.Builder builder, String authServerBaseUri, String internalSecret,
                    Duration timeout, Duration cacheTtl) {
        this.webClient = builder.baseUrl(authServerBaseUri).build();
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
        this.timeout = timeout;
        this.cached = Mono.defer(this::fetch)
                .cache(value -> cacheTtl, error -> FAILURE_TTL, () -> FAILURE_TTL);
    }

    /** 캐시된 통계. auth-server 불능이면 빈 Mono — 호출자가 503으로 옮긴다. */
    public Mono<Map<String, Object>> stats() {
        return cached;
    }

    private Mono<Map<String, Object>> fetch() {
        if (internalSecret.isEmpty()) {
            // 비밀이 없으면 호출해봐야 403이다. PAT 교환과 같은 fail-closed.
            log.debug("AGENT_INTERNAL_SECRET 없음 — PAT 통계 조회 불가");
            return Mono.empty();
        }
        return webClient.get()
                .uri("/internal/pat/stats")
                .header("X-Internal-Secret", internalSecret)
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        log.warn("PAT 통계 조회 실패 HTTP {}", response.statusCode().value());
                        return response.releaseBody().then(Mono.<Map<String, Object>>empty());
                    }
                    return response.bodyToMono(JSON_MAP);
                })
                .timeout(timeout)
                .onErrorResume(e -> {
                    log.warn("PAT 통계 조회 실패 — {}", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}
