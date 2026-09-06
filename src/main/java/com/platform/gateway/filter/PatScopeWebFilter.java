package com.platform.gateway.filter;

import com.nimbusds.jwt.JWTClaimsSet;
import com.platform.gateway.security.JwtClaims;
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
import java.util.List;

/**
 * 개인 API 토큰(PAT) 스코프 강제. {@link PatExchangeWebFilter}(order -101)가 PAT를 플랫폼 JWT로
 * 바꿔치기한 <b>직후</b>에 돌면서, 그 JWT의 {@code scope} 클레임이 이 요청 경로·메서드를 감당하는지 본다.
 *
 * <p><b>PAT에만 손댄다.</b> 판별 기준은 {@code provider=PAT} 클레임 하나다 — 브라우저 세션 JWT에는 이
 * 클레임이 없으므로 사람이 쓰는 요청은 이 필터를 그대로 통과한다. 스코프 없는 구버전 PAT도 통과한다
 * (auth-server V5가 기존 행을 전체 스코프로 채우므로 실제로는 배포 직후 캐시된 JWT뿐이다).
 *
 * <p><b>서명은 검증하지 않는다.</b> 클레임만 읽고, 그 값으로는 <i>거부</i>만 한다. 위조 JWT는 바로 뒤
 * 보안 체인이 401로 잘라내므로 이 필터가 열어주는 구멍은 없다 — 최악이라야 401 대신 403이 나갈 뿐이다.
 *
 * <p>스코프가 교환 캐시({@code PatExchangeWebFilter})와 무관하게 JWT 안에 실려 오므로 캐시 구조는
 * 그대로다. 토큰의 스코프를 바꾸면 캐시 TTL(60초)만큼 늦게 반영된다 — 폐기와 같은 지연이다.
 */
@Component
@Order(PatScopeWebFilter.ORDER)
public class PatScopeWebFilter implements WebFilter {

    /**
     * 교환 필터(-101) 뒤. 보안 체인({@code WebFilterChainProxy})도 -100이지만 어느 쪽이 앞서든
     * 결과는 같다 — 이 필터는 거부만 하고, 보안 체인은 서명을 본다. 반드시 지켜야 하는 순서는
     * "교환보다 뒤"뿐이다(그 앞이면 아직 PAT 원문이라 읽을 JWT가 없다).
     */
    public static final int ORDER = -100;

    static final String PAT_PROVIDER = "PAT";

    private static final Logger log = LoggerFactory.getLogger(PatScopeWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        JWTClaimsSet claims = JwtClaims.parseBearer(authorization);
        if (claims == null || !PAT_PROVIDER.equals(JwtClaims.provider(claims))) {
            return chain.filter(exchange); // 세션 JWT·무토큰·JWT 아닌 Bearer는 소관이 아니다
        }

        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        String method = exchange.getRequest().getMethod().name();
        PatScopeRules.Rule rule = PatScopeRules.evaluate(path, method);

        // 금지 경로는 스코프 클레임 유무와 무관하다 — 구버전 토큰이라고 열어주면 규칙이 무의미해진다.
        if (rule instanceof PatScopeRules.Rule.Forbidden) {
            return deny(exchange, JwtClaims.subject(claims), path, "path_not_allowed_for_pat");
        }

        List<String> granted = JwtClaims.scopes(claims);
        if (granted == null) {
            return chain.filter(exchange); // 구버전 토큰(클레임 자체가 없음)
        }

        PatScopeRules.Rule.Required required = (PatScopeRules.Rule.Required) rule;
        if (!PatScopeRules.satisfies(granted, required.scopes())) {
            return deny(exchange, JwtClaims.subject(claims), path, "requires " + required.scopes());
        }
        return chain.filter(exchange);
    }

    private Mono<Void> deny(ServerWebExchange exchange, String subject, String path, String reason) {
        log.info("PAT 스코프 부족 sub={} {} — {}", subject, path, reason);
        return writeError(exchange, HttpStatus.FORBIDDEN, "insufficient_scope");
    }

    /** 본문 형식은 {@link PatExchangeWebFilter}의 {@code invalid_token}·{@code auth_unavailable}과 동일. */
    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        byte[] body = ("{\"error\":\"" + errorCode + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
