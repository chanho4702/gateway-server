package com.platform.gateway.platform;

import com.nimbusds.jwt.JWTClaimsSet;
import com.platform.gateway.filter.PatScopeWebFilter;
import com.platform.gateway.search.SearchMode;
import com.platform.gateway.search.SearchProperties;
import com.platform.gateway.security.JwtClaims;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

/**
 * {@code GET /api/platform/features} — 이 설치에서 무엇이 켜져 있는가. 프론트가 <b>메뉴 표시</b>를
 * 정하는 데 쓴다.
 *
 * <pre>{@code
 * { "search": { "mode": "opensearch", "unified": true, "reindex": true } }
 * }</pre>
 *
 * <p><b>왜 게이트웨이인가</b>: 검색 모드를 아는 유일한 컴포넌트다. 라우트 대상·헬스 표의 검색 행과
 * 같은 값 하나({@code SEARCH_MODE})에서 나오므로 화면과 라우팅이 어긋날 수 없다(설계 §6.2).
 *
 * <p><b>왜 {@link PlatformController}와 분리했나</b>: 그쪽은 {@link AdminGate}로 전역 관리자만
 * 통과시킨다. 기능 플래그는 메뉴를 그릴지 말지의 판단이라 <b>로그인한 모든 사용자</b>가 읽어야 한다 —
 * 같은 컨트롤러에 두면 관리자 판정을 태우거나, 태우지 않기 위해 그 클래스의 규칙을 흐리게 된다.
 * 보안 체인의 {@code anyExchange().authenticated()}는 그대로 받으므로 비로그인은 401이다.
 *
 * <p>PAT는 {@link PatScopeWebFilter}가 {@code /api/platform/**}을 전면 거부한다. 여기서 한 번 더
 * 닫는 것은 {@link PlatformController}와 같은 이유다 — 필터 규칙이 바뀌어도 이 경로는 열리지 않는다.
 *
 * <p>캐시 헤더를 붙이지 않는다. 값은 기동 시 고정된 설정이고, 프론트가 세션당 1회만 부른다.
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformFeaturesController {

    private final SearchProperties searchProperties;

    public PlatformFeaturesController(SearchProperties searchProperties) {
        this.searchProperties = searchProperties;
    }

    @GetMapping("/features")
    public ResponseEntity<Object> features(ServerWebExchange exchange) {
        JWTClaimsSet claims = JwtClaims.parseBearer(
                exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (claims != null && "PAT".equals(JwtClaims.provider(claims))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        SearchMode mode = searchProperties.getMode();
        return ResponseEntity.ok(Map.of("search", Map.of(
                // 라이트 모드 안내 문구를 프론트가 고르는 근거. 계약은 소문자 세 값뿐이다.
                "mode", mode.id(),
                // 통합(cross-app) 검색은 opensearch/external 전용 능력이다(설계 §7).
                "unified", mode.unified(),
                // 색인 관리 메뉴 노출. lite에는 /admin/reindex 자체가 없다(404).
                "reindex", mode.reindex())));
    }
}
