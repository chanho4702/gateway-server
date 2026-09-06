package com.platform.gateway.platform;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.nimbusds.jwt.JWTClaimsSet;
import com.platform.gateway.security.JwtClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/**
 * {@code /api/platform/**} 전역 관리자 판정. 게이트웨이는 권한 정본을 갖지 않으므로 org-service에
 * 물어본다 — 사용자 Authorization을 그대로 전달해 {@code GET /api/org/me}의 {@code globalRoles}에
 * {@code ADMIN}이 있는지 본다.
 *
 * <p><b>fail-closed.</b> org-service가 불능이면 "관리자가 아니다"가 아니라 <b>503</b>이다. 장애를
 * 권한 거부로 둔갑시키면 화면이 "권한 없음"을 띄우고 사람은 엉뚱한 곳을 뒤진다.
 *
 * <p>판정은 {@code sub} 기준 30초 캐시. 대시보드가 여러 API를 동시에 부르므로 캐시가 없으면
 * 화면 한 번에 org-service 호출이 그대로 배수로 늘어난다.
 */
@Component
public class AdminGate {

    /** 판정 결과. 세 갈래를 섞지 않는 것이 이 클래스의 전부다. */
    public enum Decision { ALLOWED, DENIED, UNAVAILABLE }

    private static final Logger log = LoggerFactory.getLogger(AdminGate.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_ENTRIES = 10_000;
    private static final String ADMIN_ROLE = "ADMIN";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final Duration timeout;
    private final Cache<String, Boolean> cache;

    @Autowired
    public AdminGate(@Value("${platform.org-service-base-uri}") String orgServiceBaseUri) {
        this(WebClient.builder(), orgServiceBaseUri, DEFAULT_TIMEOUT, Ticker.systemTicker());
    }

    /** 테스트용 — MockWebServer 주소와 가짜 시계(캐시 만료를 실시간 대기 없이 앞당긴다). */
    AdminGate(WebClient.Builder builder, String orgServiceBaseUri, Duration timeout, Ticker ticker) {
        this.webClient = builder.baseUrl(orgServiceBaseUri).build();
        this.timeout = timeout;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(CACHE_TTL)
                .ticker(ticker)
                .build();
    }

    /**
     * @param authorization 사용자 요청의 {@code Authorization} 헤더 원문. 보안 체인이 이미 검증한
     *                      JWT이므로 여기서는 캐시 키({@code sub})를 얻는 데만 파싱한다.
     */
    public Mono<Decision> check(String authorization) {
        JWTClaimsSet claims = JwtClaims.parseBearer(authorization);
        String subject = JwtClaims.subject(claims);

        if (subject != null) {
            Boolean cached = cache.getIfPresent(subject);
            if (cached != null) {
                return Mono.just(cached ? Decision.ALLOWED : Decision.DENIED);
            }
        }

        return webClient.get()
                .uri("/api/org/me")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 401 || status == 403) {
                        return response.releaseBody().thenReturn(Decision.DENIED);
                    }
                    if (status < 200 || status >= 300) {
                        return response.releaseBody().thenReturn(Decision.UNAVAILABLE);
                    }
                    return response.bodyToMono(JSON_MAP)
                            .onErrorResume(e -> Mono.empty())
                            .map(body -> isGlobalAdmin(body) ? Decision.ALLOWED : Decision.DENIED)
                            // 200인데 본문을 못 읽으면 판정 근거가 없다 — 거부가 아니라 불능이다.
                            .defaultIfEmpty(Decision.UNAVAILABLE);
                })
                .timeout(timeout)
                .onErrorResume(e -> {
                    log.warn("org-service 관리자 판정 실패 — {}", e.getClass().getSimpleName());
                    return Mono.just(Decision.UNAVAILABLE);
                })
                .doOnNext(decision -> {
                    if (subject != null && decision != Decision.UNAVAILABLE) {
                        cache.put(subject, decision == Decision.ALLOWED); // 불능은 캐시하지 않는다
                    }
                });
    }

    private static boolean isGlobalAdmin(Map<String, Object> body) {
        if (body == null || !(body.get("globalRoles") instanceof Collection<?> roles)) {
            return false;
        }
        return roles.stream().anyMatch(role -> ADMIN_ROLE.equalsIgnoreCase(String.valueOf(role)));
    }
}
