package com.platform.gateway.filter;

import java.util.Collection;
import java.util.LinkedHashSet;
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
 *   <tr><td>{@code /api/wiki/**} · {@code /api/alm/**} · {@code /api/org/**}</td>
 *       <td>{@code <제품>:read}(GET·HEAD·OPTIONS) 또는 {@code <제품>:write}</td></tr>
 *   <tr><td>{@code /api/*}{@code /admin/**}</td><td>위에 더해 {@code admin}</td></tr>
 *   <tr><td>{@code /api/migration/**} · {@code /api/agent/**}</td><td>{@code admin}</td></tr>
 *   <tr><td>{@code /api/platform/**}</td><td><b>PAT 전면 거부</b>(관리 화면 전용)</td></tr>
 *   <tr><td>그 외 {@code /api/**}</td><td><b>PAT 거부</b>(board·auth·search 등)</td></tr>
 *   <tr><td>{@code /api/}로 시작하지 않는 경로</td><td>없음 — 로그인 리다이렉트·JWKS·fallback 등 공개 흐름</td></tr>
 * </table>
 */
public final class PatScopeRules {

    private static final Set<String> PRODUCTS = Set.of("wiki", "alm", "org");
    /** 제품 스코프가 없는 대신 admin만 요구하는 접두사. */
    private static final Set<String> ADMIN_ONLY = Set.of("migration", "agent");
    private static final String ADMIN = "admin";

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
        if (matches(p, "/api/platform")) {
            // 관리자 대시보드 전용. admin 스코프가 있어도 PAT로는 열지 않는다(설계 §2.1).
            return FORBIDDEN;
        }

        String[] segments = p.split("/");
        // "" , "api" , <first> , ...
        String first = segments.length > 2 ? segments[2].toLowerCase(Locale.ROOT) : "";
        String second = segments.length > 3 ? segments[3].toLowerCase(Locale.ROOT) : "";

        if (ADMIN_ONLY.contains(first)) {
            return new Rule.Required(Set.of(ADMIN));
        }
        if (PRODUCTS.contains(first)) {
            Set<String> required = new LinkedHashSet<>();
            required.add(first + ":" + (isRead(method) ? "read" : "write"));
            if (ADMIN.equals(second)) {
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

    private static boolean isRead(String method) {
        if (method == null) {
            return false; // 알 수 없으면 더 강한 쪽(write)을 요구한다
        }
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> true;
            default -> false;
        };
    }

    /** {@code prefix} 자신 또는 그 하위 경로인지. {@code /api/platformish}가 걸리지 않게 경계를 본다. */
    private static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.equals(prefix + "/") || path.startsWith(prefix + "/");
    }
}
