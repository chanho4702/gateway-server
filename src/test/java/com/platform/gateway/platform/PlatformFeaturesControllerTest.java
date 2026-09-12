package com.platform.gateway.platform;

import com.platform.gateway.filter.PatScopeRules;
import com.platform.gateway.search.SearchMode;
import com.platform.gateway.search.SearchProperties;
import com.platform.gateway.security.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기능 플래그 API의 응답 계약. 프론트(wiki-front)가 이 shape 그대로 메뉴를 지우므로 필드 이름·타입이
 * 곧 경계면이다 — 바꾸려면 store 어댑터와 동시에 바꾼다.
 */
class PlatformFeaturesControllerTest {

    private static final String SESSION = "Bearer " + TestJwt.session("member-42");

    private WebTestClient client(SearchMode mode) {
        SearchProperties properties = new SearchProperties();
        properties.setMode(mode);
        return WebTestClient.bindToController(new PlatformFeaturesController(properties)).build();
    }

    @Test
    void opensearch_모드는_통합검색과_재색인을_켠다() {
        client(SearchMode.OPENSEARCH).get().uri("/api/platform/features")
                .header(HttpHeaders.AUTHORIZATION, SESSION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.search.mode").isEqualTo("opensearch")
                .jsonPath("$.search.unified").isEqualTo(true)
                .jsonPath("$.search.reindex").isEqualTo(true);
    }

    /** 라이트 배포에서 "검색 색인 관리" 메뉴가 죽은 화면으로 안내하지 않게 하는 값이다(설계 §6.1). */
    @Test
    void lite_모드는_둘_다_끈다() {
        client(SearchMode.LITE).get().uri("/api/platform/features")
                .header(HttpHeaders.AUTHORIZATION, SESSION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.search.mode").isEqualTo("lite")
                .jsonPath("$.search.unified").isEqualTo(false)
                .jsonPath("$.search.reindex").isEqualTo(false);
    }

    /** external은 우리가 OpenSearch를 안 띄울 뿐, 능력은 opensearch와 같다. */
    @Test
    void external_모드는_opensearch와_같은_능력을_보고한다() {
        client(SearchMode.EXTERNAL).get().uri("/api/platform/features")
                .header(HttpHeaders.AUTHORIZATION, SESSION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.search.mode").isEqualTo("external")
                .jsonPath("$.search.unified").isEqualTo(true)
                .jsonPath("$.search.reindex").isEqualTo(true);
    }

    /**
     * 관리자가 아니어도 읽는다 — 메뉴를 그릴지 말지의 판단이라 AdminGate를 태우지 않는다.
     * 비로그인 차단은 보안 체인({@code anyExchange().authenticated()})의 몫이다
     * ({@link PlatformEndpointsSecurityTest}에서 401을 실측한다).
     */
    @Test
    void 일반_사용자도_읽는다() {
        client(SearchMode.LITE).get().uri("/api/platform/features")
                .header(HttpHeaders.AUTHORIZATION, SESSION)
                .exchange()
                .expectStatus().isOk();
    }

    /** PAT는 스코프와 무관하게 /api/platform/**을 못 연다 — 필터가 먼저 막지만 여기서도 닫는다. */
    @Test
    void PAT는_admin_스코프가_있어도_거부된다() {
        client(SearchMode.OPENSEARCH).get().uri("/api/platform/features")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.pat("member-42", List.of("admin")))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("forbidden");
    }

    /** 그 앞단 규칙도 함께 못박는다 — features가 새 경로라고 예외가 생기지 않았는지. */
    @Test
    void 스코프_규칙도_features를_PAT에_열지_않는다() {
        assertThat(PatScopeRules.evaluate("/api/platform/features", "GET"))
                .isInstanceOf(PatScopeRules.Rule.Forbidden.class);
    }
}
