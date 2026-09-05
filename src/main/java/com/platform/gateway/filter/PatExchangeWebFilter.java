package com.platform.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 개인 API 토큰(PAT)을 플랫폼 JWT로 바꿔치기하는 필터.
 *
 * <p><b>왜 {@code GlobalFilter}가 아니라 {@code WebFilter}인가</b> — Spring Cloud Gateway의
 * {@code GlobalFilter}는 Security 체인({@code WebFilterChainProxy}, order -100) <i>뒤</i>에 실행된다.
 * PAT는 JWT가 아니므로 그 자리에 오기 전에 이미 401로 잘린다. 그래서 order <b>-101</b>의 순수
 * {@code WebFilter}로 보안 체인보다 앞에 서서, 헤더를 정상 JWT로 바꾼 뒤 체인을 태운다.
 * 결과적으로 다운스트림 서비스(wiki·alm·org·board)는 한 줄도 바뀌지 않는다 — 지금처럼 JWT만 본다.
 *
 * <p><b>캐시</b>는 Caffeine 인스턴스 로컬이다. Redis를 쓰지 않는다 — rate limiter처럼 Redis 부재 시
 * fail-open이 되면 인증 캐시에서는 그대로 취약점이 된다.
 */
@Component
@Order(PatExchangeWebFilter.ORDER)
public class PatExchangeWebFilter implements WebFilter {

    /** Boot 보안 체인(WebFilterChainProxy)은 -100. 그보다 한 칸 앞. */
    public static final int ORDER = -101;

    static final String PAT_PREFIX = "chanho_pat_";
    private static final String BEARER = "bearer ";
    private static final Duration POSITIVE_TTL = Duration.ofSeconds(60);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(10);
    /** 캐시된 JWT는 만료 30초 전까지만 재쓴다 — 다운스트림 도착 직후 만료되는 토큰을 넘기지 않기 위해. */
    private static final Duration EXPIRY_GUARD = Duration.ofSeconds(30);
    private static final int MAX_ENTRIES = 10_000;

    private static final Logger log = LoggerFactory.getLogger(PatExchangeWebFilter.class);

    private final PatExchangeClient client;
    private final Cache<String, CacheEntry> cache;

    public PatExchangeWebFilter(PatExchangeClient client) {
        this.client = client;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                // 성공 60s / 실패 10s — 항목마다 수명이 다르므로 고정 expireAfterWrite로는 표현할 수 없다.
                .expireAfter(new Expiry<String, CacheEntry>() {
                    @Override
                    public long expireAfterCreate(String key, CacheEntry value, long currentTime) {
                        return value.ttl().toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, CacheEntry value, long currentTime,
                                                  long currentDuration) {
                        return value.ttl().toNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, CacheEntry value, long currentTime,
                                                long currentDuration) {
                        return currentDuration; // 읽어도 수명은 연장하지 않는다(폐기 반영 지연 방지)
                    }
                })
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawToken = extractPatToken(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (rawToken == null) {
            return chain.filter(exchange); // PAT가 아닌 Bearer(JWT)·무헤더는 손대지 않는다
        }

        String key = sha256Hex(rawToken);
        if (!client.isEnabled()) {
            // 비밀 미설정 = 교환 불가. 열어주는 대신 닫는다(fail-closed).
            log.warn("PAT 교환 비활성(AGENT_INTERNAL_SECRET 없음) — 거부 tokenHash={}", hint(key));
            return unauthorized(exchange);
        }

        CacheEntry cached = cache.getIfPresent(key);
        if (cached != null && cached.usable(Instant.now())) {
            if (cached.negative()) {
                return unauthorized(exchange);
            }
            return chain.filter(withJwt(exchange, cached.accessToken()));
        }

        return client.exchange(rawToken).flatMap(result -> switch (result) {
            case PatExchangeResult.Success s -> {
                cache.put(key, CacheEntry.success(s.accessToken(),
                        Instant.now().plusSeconds(s.expiresInSeconds())));
                yield chain.filter(withJwt(exchange, s.accessToken()));
            }
            case PatExchangeResult.Invalid ignored -> {
                // 부정 캐시 10s — 무차별 대입이 매 요청 auth-server를 때리지 못하게 한다.
                cache.put(key, CacheEntry.invalid());
                log.warn("PAT 교환 거부 tokenHash={}", hint(key));
                yield unauthorized(exchange);
            }
            case PatExchangeResult.Unavailable u -> {
                // 캐시하지 않는다 — 장애는 곧 풀릴 수 있고, 그 사이 정상 토큰을 막을 이유가 없다.
                log.error("PAT 교환 불가(auth-server) reason={} tokenHash={}", u.reason(), hint(key));
                yield writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, "auth_unavailable");
            }
        });
    }

    /** {@code Authorization: Bearer chanho_pat_…} 일 때만 원문 토큰, 아니면 null. 스킴은 대소문자 무시. */
    static String extractPatToken(String authorization) {
        if (authorization == null || authorization.length() <= BEARER.length()) {
            return null;
        }
        if (!authorization.substring(0, BEARER.length()).toLowerCase(Locale.ROOT).equals(BEARER)) {
            return null;
        }
        String token = authorization.substring(BEARER.length()).trim();
        return token.startsWith(PAT_PREFIX) ? token : null;
    }

    private static ServerWebExchange withJwt(ServerWebExchange exchange, String jwt) {
        return exchange.mutate()
                .request(r -> r.headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)))
                .build();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        return writeError(exchange, HttpStatus.UNAUTHORIZED, "invalid_token");
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        byte[] body = ("{\"error\":\"" + errorCode + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** 로그에는 원문 토큰을 절대 남기지 않는다 — 해시 앞 8자만. */
    private static String hint(String sha256Hex) {
        return sha256Hex.substring(0, 8);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 캐시 항목. 성공이면 JWT와 그 만료시각을, 실패면 부정 표식만 담는다.
     *
     * @param jwtExpiresAt 성공 항목의 JWT 만료 시각(부정 항목은 null)
     */
    private record CacheEntry(String accessToken, Instant jwtExpiresAt, boolean negative, Duration ttl) {

        static CacheEntry success(String accessToken, Instant jwtExpiresAt) {
            return new CacheEntry(accessToken, jwtExpiresAt, false, POSITIVE_TTL);
        }

        static CacheEntry invalid() {
            return new CacheEntry(null, null, true, NEGATIVE_TTL);
        }

        boolean usable(Instant now) {
            return negative || now.isBefore(jwtExpiresAt.minus(EXPIRY_GUARD));
        }
    }
}
