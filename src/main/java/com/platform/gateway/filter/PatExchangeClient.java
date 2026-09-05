package com.platform.gateway.filter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 개인 API 토큰(PAT)을 플랫폼 JWT로 바꾸는 클러스터 내부 호출.
 * 계약: {@code POST {base}/internal/pat/exchange}, 헤더 {@code X-Internal-Secret},
 * 본문 {@code {"token":"chanho_pat_…"}} → 200 {@code {"accessToken","expiresInSeconds"}} · 401 · 403.
 *
 * <p>비밀({@code AGENT_INTERNAL_SECRET})이 비어 있으면 호출 자체를 하지 않는다 — 비밀 없이 보낸 요청은
 * auth-server에서 403이 되어 503(불능)으로 오해되기 때문이다. 그 판단은 필터가 하도록 {@link #isEnabled()}로 노출한다.
 */
@Component
public class PatExchangeClient {

    private static final Logger log = LoggerFactory.getLogger(PatExchangeClient.class);
    /** auth-server가 죽어 있을 때 게이트웨이 스레드를 붙잡아두지 않는다 — 단일 진입점이 함께 느려진다. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    private static final long DEFAULT_TTL_SECONDS = 300;

    private final WebClient webClient;
    private final String internalSecret;
    private final Duration timeout;

    // WebClient.Builder 빈은 이 컨텍스트에 없다(게이트웨이 스타터는 WebClient 자동설정을 끌고 오지 않는다).
    // 자동설정에 기대는 대신 직접 만든다 — 이 클라이언트가 쓰는 건 JSON 코덱 하나뿐이다.
    @Autowired
    PatExchangeClient(@Value("${platform.auth-server-base-uri}") String authServerBaseUri,
                      @Value("${platform.internal-secret:}") String internalSecret) {
        this(WebClient.builder(), authServerBaseUri, internalSecret);
    }

    /** 테스트용 — MockWebServer 주소와 임의 빌더를 직접 주입한다. */
    PatExchangeClient(WebClient.Builder builder, String authServerBaseUri, String internalSecret) {
        this(builder, authServerBaseUri, internalSecret, DEFAULT_TIMEOUT);
    }

    /**
     * 테스트용 — 타임아웃까지 지정한다. 프로덕션 값(2s)을 테스트에 그대로 쓰면 빌드 머신이 바쁠 때
     * 정상 경로가 타임아웃으로 넘어가 스위트가 흔들린다(실측). 타임아웃 자체를 보는 테스트만 짧게 잡는다.
     */
    PatExchangeClient(WebClient.Builder builder, String authServerBaseUri, String internalSecret,
                      Duration timeout) {
        this.webClient = builder.baseUrl(authServerBaseUri).build();
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
        this.timeout = timeout;
    }

    @PostConstruct
    void warnWhenSecretMissing() {
        if (!isEnabled()) {
            log.warn("AGENT_INTERNAL_SECRET가 비어 있어 개인 API 토큰(PAT) 교환이 비활성화됩니다 — "
                    + "PAT로 온 요청은 전부 401로 거부합니다(fail-closed).");
        }
    }

    /** 비밀이 설정돼 있어야만 교환을 시도한다. 비어 있으면 PAT는 전부 거부(fail-closed). */
    public boolean isEnabled() {
        return !internalSecret.isEmpty();
    }

    public Mono<PatExchangeResult> exchange(String rawToken) {
        return webClient.post()
                .uri("/internal/pat/exchange")
                .header("X-Internal-Secret", internalSecret)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ExchangeRequest(rawToken))
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(ExchangeResponse.class)
                                .map(PatExchangeClient::toResult)
                                .defaultIfEmpty(new PatExchangeResult.Unavailable("empty_body"));
                    }
                    // 401만 "토큰이 틀렸다". 403(비밀 불일치)은 배포 설정 오류이므로 불능으로 올린다 —
                    // 사용자 토큰을 무효라고 단정하면 정상 토큰까지 부정 캐시에 들어간다.
                    if (status == 401) {
                        return response.releaseBody()
                                .thenReturn((PatExchangeResult) new PatExchangeResult.Invalid());
                    }
                    return response.releaseBody()
                            .thenReturn((PatExchangeResult) new PatExchangeResult.Unavailable("status_" + status));
                })
                .timeout(timeout)
                .onErrorResume(e -> Mono.just(new PatExchangeResult.Unavailable(e.getClass().getSimpleName())));
    }

    private static PatExchangeResult toResult(ExchangeResponse body) {
        if (body.accessToken() == null || body.accessToken().isBlank()) {
            return new PatExchangeResult.Unavailable("empty_access_token");
        }
        long ttl = body.expiresInSeconds() > 0 ? body.expiresInSeconds() : DEFAULT_TTL_SECONDS;
        return new PatExchangeResult.Success(body.accessToken(), ttl);
    }

    record ExchangeRequest(String token) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExchangeResponse(String accessToken, long expiresInSeconds) {}
}
