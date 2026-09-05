package com.platform.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final Duration INVALID_TTL = Duration.ofSeconds(10);
    /**
     * 불능(503) 항목의 수명. 아주 짧게 잡는 이유가 양쪽에 있다 — 403 비밀 불일치처럼 영구적인 설정 오류일 때
     * 모든 PAT 요청이 2초 타임아웃까지 auth-server를 계속 때리는 폭주를 막아야 하고(캐시가 필요한 이유),
     * 동시에 장애가 풀린 뒤 정상 토큰이 갇히는 시간이 이 값을 넘으면 안 된다(짧아야 하는 이유).
     */
    private static final Duration UNAVAILABLE_TTL = Duration.ofSeconds(2);
    /** 캐시된 JWT는 만료 30초 전까지만 재쓴다 — 다운스트림 도착 직후 만료되는 토큰을 넘기지 않기 위해. */
    private static final Duration EXPIRY_GUARD = Duration.ofSeconds(30);
    private static final int MAX_ENTRIES = 10_000;

    private static final Logger log = LoggerFactory.getLogger(PatExchangeWebFilter.class);

    private final PatExchangeClient client;
    private final Cache<String, CacheEntry> cache;

    // 생성자가 둘이면 스프링은 기본 생성자를 찾다가 기동에 실패한다 — 주입 대상을 명시한다.
    @Autowired
    public PatExchangeWebFilter(PatExchangeClient client) {
        this(client, Ticker.systemTicker());
    }

    /** 테스트용 — 가짜 Ticker로 캐시 만료를 실시간 대기 없이 앞당긴다. */
    PatExchangeWebFilter(PatExchangeClient client, Ticker ticker) {
        this.client = client;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                // 성공 60s / 무효 10s / 불능 2s — 항목마다 수명이 다르므로 고정 expireAfterWrite로는 표현할 수 없다.
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
            // 요청마다가 아니라 기동 시 WARN 한 줄로 알린다(PatExchangeClient) — 여기서 WARN을 찍으면
            // 설정이 빠진 환경에서 PAT 트래픽만큼 경고가 쌓여 진짜 경고가 묻힌다.
            log.debug("PAT 교환 비활성(AGENT_INTERNAL_SECRET 없음) — 거부 tokenHash={}", hint(key));
            return unauthorized(exchange);
        }

        CacheEntry cached = cache.getIfPresent(key);
        if (cached != null && cached.usable(Instant.now())) {
            return switch (cached.kind()) {
                case SUCCESS -> chain.filter(withJwt(exchange, cached.accessToken()));
                case INVALID -> unauthorized(exchange);
                case UNAVAILABLE -> writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, "auth_unavailable");
            };
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
                // 2초만 캐시한다 — 403 비밀 불일치처럼 영구적 설정 오류일 때 모든 PAT 요청이
                // 2초 타임아웃까지 auth-server를 때리는 폭주를 막되, 장애가 풀리면 곧바로 재시도한다.
                cache.put(key, CacheEntry.unavailable());
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

    /** 캐시 항목의 종류. 셋 다 수명이 다르고, 히트했을 때 내보내는 응답도 다르다. */
    private enum Kind { SUCCESS, INVALID, UNAVAILABLE }

    /**
     * 캐시 항목. 성공이면 JWT와 그 만료시각을, 실패면 종류만 담는다.
     *
     * @param jwtExpiresAt 성공 항목의 JWT 만료 시각(실패 항목은 null)
     */
    private record CacheEntry(Kind kind, String accessToken, Instant jwtExpiresAt, Duration ttl) {

        static CacheEntry success(String accessToken, Instant jwtExpiresAt) {
            return new CacheEntry(Kind.SUCCESS, accessToken, jwtExpiresAt, POSITIVE_TTL);
        }

        static CacheEntry invalid() {
            return new CacheEntry(Kind.INVALID, null, null, INVALID_TTL);
        }

        static CacheEntry unavailable() {
            return new CacheEntry(Kind.UNAVAILABLE, null, null, UNAVAILABLE_TTL);
        }

        /** 실패 항목은 TTL이 곧 수명이다. 성공 항목만 JWT 만료 30초 가드를 추가로 본다. */
        boolean usable(Instant now) {
            return kind != Kind.SUCCESS || now.isBefore(jwtExpiresAt.minus(EXPIRY_GUARD));
        }
    }
}
