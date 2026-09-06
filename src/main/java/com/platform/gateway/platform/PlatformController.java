package com.platform.gateway.platform;

import com.nimbusds.jwt.JWTClaimsSet;
import com.platform.gateway.filter.PatScopeWebFilter;
import com.platform.gateway.security.JwtClaims;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 관리자 대시보드 전용 API. 게이트웨이가 직접 응답한다 — 라우트가 아니다.
 *
 * <p>여기 있는 이유: 헬스 집계는 <b>모든 서비스에 대한 프로브</b>이므로 어느 한 서비스에 두면 그
 * 서비스가 죽는 순간 점검 화면 자체가 죽는다. 단일 진입점이자 모든 주소를 이미 아는 게이트웨이가
 * 유일하게 맞는 자리다.
 *
 * <p>접근 통제는 두 겹이다 — 보안 체인이 JWT 없는 요청을 401로 자르고({@code anyExchange().authenticated()}),
 * 그 뒤 {@link AdminGate}가 전역 관리자만 통과시킨다. PAT는 스코프와 무관하게 금지다
 * ({@link PatScopeWebFilter}가 먼저 막지만, 여기서도 한 번 더 닫는다).
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    private final AdminGate adminGate;
    private final HealthAggregator healthAggregator;
    private final AuthStatsClient authStatsClient;

    public PlatformController(AdminGate adminGate, HealthAggregator healthAggregator,
                              AuthStatsClient authStatsClient) {
        this.adminGate = adminGate;
        this.healthAggregator = healthAggregator;
        this.authStatsClient = authStatsClient;
    }

    /** 플랫폼 전 컴포넌트 상태. 20초 캐시라 폴링을 그보다 자주 해도 서비스 부하는 늘지 않는다. */
    @GetMapping("/health")
    public Mono<ResponseEntity<Object>> health(ServerWebExchange exchange) {
        return authorize(exchange, () -> healthAggregator.report()
                .map(report -> ResponseEntity.ok((Object) report)));
    }

    /** 개인 API 토큰 현황 — auth-server 내부 엔드포인트를 그대로 옮긴다(60초 캐시). */
    @GetMapping("/stats/tokens")
    public Mono<ResponseEntity<Object>> tokenStats(ServerWebExchange exchange) {
        return authorize(exchange, () -> authStatsClient.stats()
                .map(stats -> ResponseEntity.ok((Object) stats))
                .defaultIfEmpty(error(HttpStatus.SERVICE_UNAVAILABLE, "auth_unavailable")));
    }

    /**
     * 관리자 판정 후 본문을 만든다. 실패 세 갈래를 섞지 않는다 —
     * 관리자 아님 403 {@code forbidden} · org-service 불능 503 {@code org_unavailable} · PAT 403 {@code forbidden}.
     */
    private Mono<ResponseEntity<Object>> authorize(ServerWebExchange exchange,
                                                   java.util.function.Supplier<Mono<ResponseEntity<Object>>> body) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        JWTClaimsSet claims = JwtClaims.parseBearer(authorization);
        if (claims != null && "PAT".equals(JwtClaims.provider(claims))) {
            // 관리 화면 전용 — 개인 API 토큰으로는 admin 스코프가 있어도 열지 않는다(설계 §2.1).
            return Mono.just(error(HttpStatus.FORBIDDEN, "forbidden"));
        }
        return adminGate.check(authorization).flatMap(decision -> switch (decision) {
            case ALLOWED -> body.get();
            case DENIED -> Mono.just(error(HttpStatus.FORBIDDEN, "forbidden"));
            case UNAVAILABLE -> Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE, "org_unavailable"));
        });
    }

    /** 오류 본문은 게이트웨이의 다른 오류({@code invalid_token}·{@code insufficient_scope})와 같은 형식. */
    private static ResponseEntity<Object> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
