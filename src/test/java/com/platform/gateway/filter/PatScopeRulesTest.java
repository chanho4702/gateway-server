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
        assertThat(required("/api/board/posts", "GET")).containsExactly("board:read");
        assertThat(required("/api/board/posts/9/comments", "POST")).containsExactly("board:write");
        assertThat(required("/api/board/posts/9", "DELETE")).containsExactly("board:write");
    }

    /**
     * 검색은 질의 표면이 GraphQL 단일 URL(POST)뿐이라 메서드로 읽기/쓰기를 가르지 않는다.
     * GET만 read로 인정하면 search:read로는 아무것도 호출할 수 없다.
     */
    @Test
    void 검색은_메서드와_무관하게_search_read만_요구한다() {
        assertThat(required("/api/search/graphql", "POST")).containsExactly("search:read");
        assertThat(required("/api/search/graphql", "GET")).containsExactly("search:read");
        assertThat(required("/api/search", "OPTIONS")).containsExactly("search:read");
    }

    /** 색인을 바꾸는 유일한 경로. 제품 스코프 + admin — 다른 제품의 admin 경로와 같은 규칙이다. */
    @Test
    void 재색인은_search_read에_admin을_추가로_요구한다() {
        assertThat(required("/api/search/admin/reindex", "POST"))
                .containsExactlyInAnyOrder("search:read", "admin");
        assertThat(PatScopeRules.satisfies(List.of("search:read"), Set.of("search:read", "admin"))).isFalse();
    }

    /** 어떤 메서드로도 search:write를 요구하지 않는다 — 그런 스코프는 발급되지 않는다. */
    @Test
    void 검색은_어떤_메서드로도_write를_요구하지_않는다() {
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            assertThat(required("/api/search/graphql", method)).doesNotContain("search:write");
        }
    }

    @Test
    void 제품_admin_경로는_admin을_추가로_요구한다() {
        assertThat(required("/api/wiki/admin/stats", "GET")).containsExactlyInAnyOrder("wiki:read", "admin");
        assertThat(required("/api/org/admin/stats", "GET")).containsExactlyInAnyOrder("org:read", "admin");
        assertThat(required("/api/alm/admin/audit", "POST")).containsExactlyInAnyOrder("alm:write", "admin");
        assertThat(required("/api/board/admin/purge", "POST")).containsExactlyInAnyOrder("board:write", "admin");
    }

    @Test
    void 이관과_에이전트는_제품_스코프_없이_admin만_요구한다() {
        assertThat(required("/api/migration/jobs", "POST")).containsExactly("admin");
        assertThat(required("/api/agent/personas", "GET")).containsExactly("admin");
        assertThat(required("/api/agent/mcp/tools/call", "POST")).containsExactly("admin");
    }

    /** 정확히 그 경로 하나. 라우트도 Path=/api/me 단건이라 하위 경로를 열어줄 이유가 없다. */
    @Test
    void 내_프로필은_정확히_일치할_때만_허용된다() {
        assertThat(required("/api/me", "GET")).isEmpty();
        forbidden("/api/me/", "GET");
        forbidden("/api/me/tokens", "DELETE");
        forbidden("/api/mercury", "GET");
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
        forbidden("/api/auth/tokens", "POST");
        forbidden("/api/unknown", "GET");
        forbidden("/api/boards/posts", "GET"); // board 아님
        forbidden("/api/searching", "GET");    // search 아님
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

    // --- 관리 경로 표(P-03) ------------------------------------------------------
    // 실제 경로는 org-service·wiki-backend·alm-backend 컨트롤러 매핑에서 옮겼다(2026-10-18 main 기준).

    /** org-service — 권한 원장·초대·메일 설정은 메서드 무관 전부 관리 작업이다(조회도 리소스 ADMIN 전용). */
    @Test
    void org_권한_초대_설정은_메서드_무관_admin을_요구한다() {
        assertThat(required("/api/org/grants", "GET")).containsExactlyInAnyOrder("org:read", "admin");
        assertThat(required("/api/org/grants/audit", "GET")).containsExactlyInAnyOrder("org:read", "admin");
        assertThat(required("/api/org/grants", "POST")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/grants/5", "PATCH")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/grants/5", "DELETE")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/invitations", "GET")).containsExactlyInAnyOrder("org:read", "admin");
        assertThat(required("/api/org/invitations", "POST")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/invitations/3/resend", "POST")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/invitations/3", "DELETE")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/settings/mail", "GET")).containsExactlyInAnyOrder("org:read", "admin");
        assertThat(required("/api/org/settings/mail", "PUT")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/settings/mail/test", "POST")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/settings/mail/log/9/retry", "POST")).containsExactlyInAnyOrder("org:write", "admin");
    }

    /** 구성원 — 상태 전이·승인·에이전트 등록·남의 아바타 변경과 관리 화면 조회(승인 대기·이력)만 admin. */
    @Test
    void org_구성원_관리는_admin_일반_조회는_org_read() {
        assertThat(required("/api/org/members/42", "PATCH")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/members/42/approve", "POST")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/members/agents", "POST")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/members/42/avatar", "PUT")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/members/42/avatar", "DELETE")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/members/pending", "GET")).containsExactlyInAnyOrder("org:read", "admin");
        assertThat(required("/api/org/members/42/events", "GET")).containsExactlyInAnyOrder("org:read", "admin");
        // 일반 조회는 관리가 아니다 — ALM·위키 화면이 쓰는 멤버 목록·상세·아바타
        assertThat(required("/api/org/members", "GET")).containsExactly("org:read");
        assertThat(required("/api/org/members/page", "GET")).containsExactly("org:read");
        assertThat(required("/api/org/members/42", "GET")).containsExactly("org:read");
        assertThat(required("/api/org/members/42/avatar", "GET")).containsExactly("org:read");
    }

    /** 팀 — 쓰기(생성·수정·삭제·팀원·리더)만 admin, 조회는 org:read. 본인 프로필(me)은 관리가 아니다. */
    @Test
    void org_팀_쓰기는_admin_조회와_본인은_아니다() {
        assertThat(required("/api/org/teams", "POST")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/teams/1", "PUT")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/teams/1", "DELETE")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/teams/1/members/42", "PUT")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/teams/1/members/42", "PATCH")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/teams/1/members/42", "DELETE")).containsExactlyInAnyOrder("org:write", "admin");
        assertThat(required("/api/org/teams", "GET")).containsExactly("org:read");
        assertThat(required("/api/org/teams/1/members", "GET")).containsExactly("org:read");
        assertThat(required("/api/org/me", "GET")).containsExactly("org:read");
        assertThat(required("/api/org/me/permissions", "GET")).containsExactly("org:read");
        assertThat(required("/api/org/me/avatar", "PUT")).containsExactly("org:write");
    }

    /** 위키 — 페이지 제한(권한) 변경, 스페이스 삭제, 휴지통 비우기·영구 삭제(스페이스 ADMIN 전용). */
    @Test
    void wiki_권한_변경과_영구_삭제는_admin을_요구한다() {
        assertThat(required("/api/wiki/pages/7/restrictions", "PUT")).containsExactlyInAnyOrder("wiki:write", "admin");
        assertThat(required("/api/wiki/spaces/3", "DELETE")).containsExactlyInAnyOrder("wiki:write", "admin");
        assertThat(required("/api/wiki/spaces/3/trash", "DELETE")).containsExactlyInAnyOrder("wiki:write", "admin");
        assertThat(required("/api/wiki/pages/7/purge", "DELETE")).containsExactlyInAnyOrder("wiki:write", "admin");
        // 그 밖의 위키 작업은 제품 스코프만
        assertThat(required("/api/wiki/pages/7/restrictions", "GET")).containsExactly("wiki:read");
        assertThat(required("/api/wiki/spaces/3", "PUT")).containsExactly("wiki:write");
        assertThat(required("/api/wiki/spaces/3/trash", "GET")).containsExactly("wiki:read");
        assertThat(required("/api/wiki/pages/7/restore", "POST")).containsExactly("wiki:write");
        assertThat(required("/api/wiki/pages/7", "DELETE")).containsExactly("wiki:write");
        assertThat(required("/api/wiki/spaces/3/pages/children", "GET")).containsExactly("wiki:read");
    }

    /** ALM — 전역 설정 레지스트리 쓰기(전역 관리자)·프로젝트 설정 쓰기·프로젝트 삭제(프로젝트 ADMIN). */
    @Test
    void alm_설정_쓰기와_프로젝트_삭제는_admin을_요구한다() {
        assertThat(required("/api/alm/settings/statuses", "POST")).containsExactlyInAnyOrder("alm:write", "admin");
        assertThat(required("/api/alm/settings/schemes/2", "PUT")).containsExactlyInAnyOrder("alm:write", "admin");
        assertThat(required("/api/alm/settings/priorities/2/move", "POST")).containsExactlyInAnyOrder("alm:write", "admin");
        assertThat(required("/api/alm/settings/issue-types/2", "DELETE")).containsExactlyInAnyOrder("alm:write", "admin");
        assertThat(required("/api/alm/projects/5/settings/scheme", "PUT")).containsExactlyInAnyOrder("alm:write", "admin");
        assertThat(required("/api/alm/projects/5/settings/custom-body", "PUT")).containsExactlyInAnyOrder("alm:write", "admin");
        assertThat(required("/api/alm/projects/5", "DELETE")).containsExactlyInAnyOrder("alm:write", "admin");
        assertThat(required("/api/alm/projects/5/permanent", "DELETE")).containsExactlyInAnyOrder("alm:write", "admin");
        // 설정 조회는 이슈 화면이 쓰므로 alm:read 그대로
        assertThat(required("/api/alm/settings/statuses", "GET")).containsExactly("alm:read");
        assertThat(required("/api/alm/projects/5/settings", "GET")).containsExactly("alm:read");
        assertThat(required("/api/alm/projects/5", "PUT")).containsExactly("alm:write");
        assertThat(required("/api/alm/projects/5/issues/import", "POST")).containsExactly("alm:write");
    }

    /**
     * 개발 기록(alm V41, 2026-10-25) — 표를 바꾸지 않고 기존 규칙에 든다: 커밋 수집은 alm:write, 조회는 alm:read,
     * 저장소 등록 쓰기는 프로젝트 설정 쓰기(/api/alm/projects/{id}/settings/**)라 admin까지.
     */
    @Test
    void alm_개발_기록_경로는_기존_규칙에_든다() {
        assertThat(required("/api/alm/dev/commits", "POST")).containsExactly("alm:write");
        assertThat(required("/api/alm/issues/3/development", "GET")).containsExactly("alm:read");
        assertThat(required("/api/alm/projects/5/commits", "GET")).containsExactly("alm:read");
        assertThat(required("/api/alm/projects/5/settings/repositories", "GET")).containsExactly("alm:read");
        assertThat(required("/api/alm/projects/5/settings/repositories", "PUT")).containsExactlyInAnyOrder("alm:write", "admin");
    }

    /**
     * 경로 정규화 — 다운스트림(Spring MVC)은 빈 세그먼트·`;매트릭스`·퍼센트 인코딩·`.`/`..`을 정리한 뒤
     * 핸들러를 고른다. 규칙도 같은 경로로 보고 판정해야 변형으로 admin을 비켜 갈 수 없다.
     */
    @Test
    void 관리_경로는_변형된_표기로도_우회되지_않는다() {
        assertThat(required("/api/org//grants", "POST")).contains("admin");
        assertThat(required("/api/org/grants;jsessionid=x", "POST")).contains("admin");
        assertThat(required("/api/org/%67rants", "POST")).contains("admin");
        assertThat(required("/api/org/GRANTS", "POST")).contains("admin");
        assertThat(required("/api/org/x/../grants", "POST")).contains("admin");
        assertThat(required("/api/org/./grants/", "POST")).contains("admin");
        assertThat(required("/api/wiki/admin;x/stats", "GET")).contains("admin");
        assertThat(required("/api/%77iki/admin/stats", "GET")).containsExactlyInAnyOrder("wiki:read", "admin");
        forbidden("/api/./platform/health", "GET");
        forbidden("/api/platform;x/health", "GET");
        forbidden("/api/wiki/../platform/health", "GET");
    }
}
