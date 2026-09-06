package com.platform.gateway.filter;

import com.platform.gateway.filter.PatScopeRules.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 스코프 규칙표(설계 §1.3) 그 자체. 리액터 배관 없이 경로·메서드 → 요구 스코프만 본다. */
class PatScopeRulesTest {

    private static Set<String> required(String path, String method) {
        Rule rule = PatScopeRules.evaluate(path, method);
        assertThat(rule).as(method + " " + path + "는 PAT 허용 경로여야 한다").isInstanceOf(Rule.Required.class);
        return ((Rule.Required) rule).scopes();
    }

    private static void forbidden(String path, String method) {
        assertThat(PatScopeRules.evaluate(path, method))
                .as(method + " " + path + "는 PAT 금지여야 한다")
                .isInstanceOf(Rule.Forbidden.class);
    }

    @Test
    void 읽기는_제품_read_쓰기는_제품_write를_요구한다() {
        assertThat(required("/api/wiki/pages/7", "GET")).containsExactly("wiki:read");
        assertThat(required("/api/wiki/pages", "POST")).containsExactly("wiki:write");
        assertThat(required("/api/alm/issues/3", "PATCH")).containsExactly("alm:write");
        assertThat(required("/api/alm/issues", "HEAD")).containsExactly("alm:read");
        assertThat(required("/api/org/members", "DELETE")).containsExactly("org:write");
        assertThat(required("/api/org/me", "OPTIONS")).containsExactly("org:read");
    }

    @Test
    void 제품_admin_경로는_admin을_추가로_요구한다() {
        assertThat(required("/api/wiki/admin/stats", "GET")).containsExactlyInAnyOrder("wiki:read", "admin");
        assertThat(required("/api/org/admin/stats", "GET")).containsExactlyInAnyOrder("org:read", "admin");
        assertThat(required("/api/alm/admin/audit", "POST")).containsExactlyInAnyOrder("alm:write", "admin");
    }

    @Test
    void 이관과_에이전트는_제품_스코프_없이_admin만_요구한다() {
        assertThat(required("/api/migration/jobs", "POST")).containsExactly("admin");
        assertThat(required("/api/agent/personas", "GET")).containsExactly("admin");
        assertThat(required("/api/agent/mcp/tools/call", "POST")).containsExactly("admin");
    }

    @Test
    void 내_프로필은_스코프_없이_허용된다() {
        assertThat(required("/api/me", "GET")).isEmpty();
    }

    /** 관리 화면 전용 — admin 스코프를 가진 PAT도 열지 못한다(설계 §2.1). */
    @Test
    void 플랫폼_관리_API는_PAT_전면_금지다() {
        forbidden("/api/platform/health", "GET");
        forbidden("/api/platform/stats/tokens", "GET");
        assertThat(PatScopeRules.satisfies(List.of("admin", "wiki:write"), Set.of())).isTrue(); // 규칙과 무관하게
    }

    @Test
    void 표에_없는_접두사는_PAT를_거부한다() {
        forbidden("/api/board/posts", "GET");
        forbidden("/api/auth/tokens", "POST");
        forbidden("/api/search/graphql", "POST");
        forbidden("/api/unknown", "GET");
    }

    /** 로그인 리다이렉트·JWKS·fallback은 PAT가 끼어들 자리가 아니지만, 막으면 공개 흐름만 깨진다. */
    @Test
    void api가_아닌_경로는_규칙_대상이_아니다() {
        assertThat(required("/oauth2/authorization/keycloak", "GET")).isEmpty();
        assertThat(required("/.well-known/jwks.json", "GET")).isEmpty();
        assertThat(required("/fallback/board", "GET")).isEmpty();
    }

    /** 경계 실수 방지 — /api/platformish는 platform이 아니다. */
    @Test
    void 접두사_경계를_문자열_시작으로만_판단하지_않는다() {
        forbidden("/api/platformish", "GET"); // 표에 없는 접두사라서 금지(platform 규칙 때문이 아님)
        assertThat(required("/api/wiki", "GET")).containsExactly("wiki:read");
        assertThat(required("/api/wiki/administrators", "GET")).containsExactly("wiki:read"); // admin 아님
    }

    @Test
    void write는_같은_제품의_read를_포함한다() {
        assertThat(PatScopeRules.satisfies(List.of("wiki:write"), Set.of("wiki:read"))).isTrue();
        assertThat(PatScopeRules.satisfies(List.of("wiki:read"), Set.of("wiki:write"))).isFalse();
        assertThat(PatScopeRules.satisfies(List.of("alm:write"), Set.of("wiki:read"))).isFalse();
    }

    @Test
    void 요구_스코프가_여럿이면_전부_있어야_한다() {
        Set<String> wikiAdminRead = Set.of("wiki:read", "admin");
        assertThat(PatScopeRules.satisfies(List.of("wiki:write"), wikiAdminRead)).isFalse();
        assertThat(PatScopeRules.satisfies(List.of("admin"), wikiAdminRead)).isFalse();
        assertThat(PatScopeRules.satisfies(List.of("wiki:write", "admin"), wikiAdminRead)).isTrue();
    }

    @Test
    void 스코프가_비어_있으면_조건이_있는_경로는_전부_거부다() {
        assertThat(PatScopeRules.satisfies(List.of(), Set.of("wiki:read"))).isFalse();
        assertThat(PatScopeRules.satisfies(List.of(), Set.of())).isTrue();
    }
}
