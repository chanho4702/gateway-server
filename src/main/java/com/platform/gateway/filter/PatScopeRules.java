package com.platform.gateway.filter;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 개인 API 토큰(PAT) 스코프 규칙 — 경로·메서드에서 "무엇이 필요한가"를 계산하는 순수 함수.
 *
 * <p>필터에서 분리한 이유는 하나다: 이 표가 곧 계약이고, 계약은 리액터 배관 없이 읽고 시험할 수 있어야 한다.
 *
 * <table>
 *   <caption>경로 → 요구 스코프</caption>
 *   <tr><td>{@code /api/me}</td><td>없음(항상 허용)</td></tr>
 *   <tr><td>{@code /api/wiki/**} · {@code /api/alm/**} · {@code /api/org/**} · {@code /api/board/**}</td>
 *       <td>{@code <제품>:read}(GET·HEAD·OPTIONS) 또는 {@code <제품>:write}</td></tr>
 *   <tr><td>{@code /api/search/**}</td><td>{@code search:read} — 메서드 무관(아래 설명)</td></tr>
 *   <tr><td>{@code /api/*}{@code /admin/**}</td><td>위에 더해 {@code admin}</td></tr>
 *   <tr><td>{@link #ADMIN_PATHS 관리 경로 표}(권한·구성원·메일·초대·팀, 위키/ALM 권한·설정·영구 삭제)</td>
 *       <td>위에 더해 {@code admin}</td></tr>
 *   <tr><td>{@code /api/migration/**} · {@code /api/agent/**}</td><td>{@code admin}</td></tr>
 *   <tr><td>{@code /api/platform/**}</td><td><b>PAT 전면 거부</b>(관리 화면 전용)</td></tr>
 *   <tr><td>그 외 {@code /api/**}</td><td><b>PAT 거부</b>(auth 등)</td></tr>
 *   <tr><td>{@code /api/}로 시작하지 않는 경로</td><td>없음 — 로그인 리다이렉트·JWKS·fallback 등 공개 흐름</td></tr>
 * </table>
 *
 * <p><b>검색만 메서드를 보지 않는 이유.</b> search-service의 질의 표면은 GraphQL 단일 URL
 * ({@code POST /api/search/graphql}) 하나뿐이고 GET 검색 엔드포인트가 없다. 읽기를 GET으로만
 * 인정하면 {@code search:read}로는 검색을 한 번도 호출할 수 없다. 그래서 검색은 쓰기 스코프를
 * 아예 만들지 않고({@code search:write} 없음) 메서드와 무관하게 {@code search:read}만 요구한다 —
 * 색인을 바꾸는 유일한 경로인 {@code /api/search/admin/reindex}는 {@code admin}이 따로 지킨다.
 *
 * <p><b>경로는 정규화해서 본다.</b> 다운스트림(Spring MVC)은 빈 세그먼트·{@code ;매트릭스}·퍼센트 인코딩·
 * {@code .}/{@code ..}을 정리한 뒤 핸들러를 고른다. 규칙이 원문 문자열만 보면 {@code /api/org//grants}나
 * {@code /api/org/%67rants}로 관리 경로 요구를 비켜 갈 수 있으므로, 같은 정리를 거친 세그먼트로 판정한다.
 */
public final class PatScopeRules {

    private static final Set<String> PRODUCTS = Set.of("wiki", "alm", "org", "board");
    /** 제품 스코프가 없는 대신 admin만 요구하는 접두사. */
    private static final Set<String> ADMIN_ONLY = Set.of("migration", "agent");
    /** 읽기 전용 제품 — 메서드와 무관하게 {@code <제품>:read}만 요구한다(클래스 주석 참고). */
    private static final Set<String> READ_ONLY_PRODUCTS = Set.of("search");
    private static final String ADMIN = "admin";

    /** 관리 경로 표의 메서드 조건. */
    private enum On { ANY, READ, WRITE, DELETE }

    private record AdminPath(On on, String[] pattern) {
        static AdminPath of(On on, String pattern) {
            return new AdminPath(on, pattern.substring(1).split("/"));
        }
    }

    /**
     * 관리 경로 표(P-03) — 두 번째 세그먼트가 {@code admin}이 아니어도 관리 작업인 경로. 실제 경로는 각 서비스
     * 컨트롤러 매핑에서 옮겼다(2026-10-18 main). {@code *}는 세그먼트 하나, 끝의 {@code **}는 0개 이상.
     * 서비스에 관리 API가 생기면 {@code /api/<제품>/admin/**} 아래에 두는 것이 원칙이고, 이 표는 그 밖에 이미
     * 있는 것들의 목록이다. 새 경로를 여기 더할 때는 {@code PatScopeRulesTest}에 같은 줄을 더한다.
     */
    private static final List<AdminPath> ADMIN_PATHS = List.of(
            // org-service — 권한 원장(조회도 리소스 ADMIN 전용)·초대·메일 설정: 메서드 무관
            AdminPath.of(On.ANY, "/api/org/grants/**"),
            AdminPath.of(On.ANY, "/api/org/invitations/**"),
            AdminPath.of(On.ANY, "/api/org/settings/**"),
            // 구성원 — 상태 전이(PATCH /{id})·승인·에이전트 등록·남의 아바타, 관리 화면 조회
            AdminPath.of(On.WRITE, "/api/org/members/*"),
            AdminPath.of(On.WRITE, "/api/org/members/*/approve"),
            AdminPath.of(On.WRITE, "/api/org/members/agents/**"),
            AdminPath.of(On.WRITE, "/api/org/members/*/avatar"),
            AdminPath.of(On.READ, "/api/org/members/pending"),
            AdminPath.of(On.READ, "/api/org/members/*/events"),
            // 팀 — 생성·수정·삭제·팀원·리더 지정
            AdminPath.of(On.WRITE, "/api/org/teams/**"),
            // wiki-backend — 페이지 제한(권한) 변경, 스페이스 삭제, 휴지통 비우기·영구 삭제(스페이스 ADMIN)
            AdminPath.of(On.WRITE, "/api/wiki/pages/*/restrictions"),
            AdminPath.of(On.DELETE, "/api/wiki/spaces/*"),
            AdminPath.of(On.DELETE, "/api/wiki/spaces/*/trash"),
            AdminPath.of(On.DELETE, "/api/wiki/pages/*/purge"),
            // alm-backend — 전역 설정 레지스트리 쓰기(전역 관리자), 프로젝트 설정 쓰기·프로젝트 삭제(프로젝트 ADMIN)
            AdminPath.of(On.WRITE, "/api/alm/settings/**"),
            AdminPath.of(On.WRITE, "/api/alm/projects/*/settings/**"),
            AdminPath.of(On.DELETE, "/api/alm/projects/*"),
            AdminPath.of(On.DELETE, "/api/alm/projects/*/permanent"));

    private PatScopeRules() {
    }

    /** 경로 판정 결과. {@link Forbidden}은 스코프를 아무리 넉넉히 가져도 PAT로는 못 가는 경로다. */
    public sealed interface Rule {

        /** 통과에 필요한 스코프 전부(빈 집합이면 조건 없이 허용). */
        record Required(Set<String> scopes) implements Rule {}

        /** PAT 자체가 금지된 경로. */
        record Forbidden() implements Rule {}
    }

    private static final Rule.Forbidden FORBIDDEN = new Rule.Forbidden();
    private static final Rule.Required NONE = new Rule.Required(Set.of());

    public static Rule evaluate(String path, String method) {
        String p = path == null ? "" : path;
        if (!p.startsWith("/api/") && !p.equals("/api")) {
            // /oauth2/**, /login/**, /invite/**, /.well-known/**, /fallback/** — PAT를 들고 와도 의미가 없고,
            // 여기서 막으면 공개 흐름만 깨진다.
            return NONE;
        }
        // 정확히 이 경로 하나. 라우트도 Path=/api/me 단건이므로 하위 경로를 열어줄 이유가 없다.
        if (p.equals("/api/me")) {
            return NONE;
        }

        List<String> segments = normalize(p);
        if (segments == null || segments.isEmpty() || !segments.get(0).equals("api")) {
            return FORBIDDEN; // 해석 불가(잘못된 인코딩) 또는 ..로 /api 밖을 가리키는 경로
        }
        String first = segments.size() > 1 ? segments.get(1) : "";
        String second = segments.size() > 2 ? segments.get(2) : "";

        if (first.equals("platform")) {
            // 관리자 대시보드 전용. admin 스코프가 있어도 PAT로는 열지 않는다(설계 §2.1).
            return FORBIDDEN;
        }
        if (ADMIN_ONLY.contains(first)) {
            return new Rule.Required(Set.of(ADMIN));
        }
        boolean readOnly = READ_ONLY_PRODUCTS.contains(first);
        if (readOnly || PRODUCTS.contains(first)) {
            Set<String> required = new LinkedHashSet<>();
            required.add(first + ":" + (readOnly || isRead(method) ? "read" : "write"));
            if (ADMIN.equals(second) || isAdminPath(segments, method)) {
                required.add(ADMIN);
            }
            return new Rule.Required(Set.copyOf(required));
        }
        return FORBIDDEN;
    }

    /** {@code *:write}는 같은 제품의 {@code read}를 포함한다 — 쓰기 권한자가 읽지 못하는 조합은 없다. */
    public static boolean satisfies(Collection<String> granted, Set<String> required) {
        if (required.isEmpty()) {
            return true;
        }
        if (granted == null) {
            return false;
        }
        Set<String> effective = new LinkedHashSet<>();
        for (String scope : granted) {
            if (scope == null || scope.isBlank()) {
                continue;
            }
            String normalized = scope.trim().toLowerCase(Locale.ROOT);
            effective.add(normalized);
            if (normalized.endsWith(":write")) {
                effective.add(normalized.substring(0, normalized.length() - ":write".length()) + ":read");
            }
        }
        return effective.containsAll(required);
    }

    /**
     * Spring MVC가 핸들러를 고르기 전에 하는 정리를 흉내 낸다: 세그먼트별 {@code ;매트릭스} 제거 →
     * 퍼센트 디코딩 → 소문자, 빈 세그먼트·{@code .} 제거, {@code ..}은 한 칸 위로. 디코딩 불가면 null.
     */
    static List<String> normalize(String rawPath) {
        List<String> out = new ArrayList<>();
        for (String rawSegment : rawPath.split("/")) {
            int semicolon = rawSegment.indexOf(';');
            String segment = semicolon >= 0 ? rawSegment.substring(0, semicolon) : rawSegment;
            try {
                segment = UriUtils.decode(segment, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
            segment = segment.toLowerCase(Locale.ROOT);
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (out.isEmpty()) {
                    return null;
                }
                out.removeLast();
                continue;
            }
            out.add(segment);
        }
        return out;
    }

    private static boolean isAdminPath(List<String> segments, String method) {
        for (AdminPath adminPath : ADMIN_PATHS) {
            if (methodMatches(adminPath.on(), method) && segmentsMatch(adminPath.pattern(), segments)) {
                return true;
            }
        }
        return false;
    }

    private static boolean methodMatches(On on, String method) {
        return switch (on) {
            case ANY -> true;
            case READ -> isRead(method);
            case WRITE -> !isRead(method);
            case DELETE -> method != null && method.equalsIgnoreCase("DELETE");
        };
    }

    private static boolean segmentsMatch(String[] pattern, List<String> segments) {
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i].equals("**") && i == pattern.length - 1) {
                return segments.size() >= i;
            }
            if (i >= segments.size()) {
                return false;
            }
            if (!pattern[i].equals("*") && !pattern[i].equals(segments.get(i))) {
                return false;
            }
        }
        return segments.size() == pattern.length;
    }

    private static boolean isRead(String method) {
        if (method == null) {
            return false; // 알 수 없으면 더 강한 쪽(write)을 요구한다
        }
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> true;
            default -> false;
        };
    }
}
