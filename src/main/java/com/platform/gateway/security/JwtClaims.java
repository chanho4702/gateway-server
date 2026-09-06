package com.platform.gateway.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code Authorization: Bearer <jwt>} 에서 <b>서명 검증 없이</b> 클레임만 읽는 도우미.
 *
 * <p>서명·만료·issuer·audience 검증은 {@code SecurityConfig}의 리소스 서버가 한다. 여기서 읽은 값으로
 * 요청을 <i>통과</i>시키는 판단은 절대 하지 않는다 — 스코프 필터는 이 값으로 <b>거부</b>만 하고,
 * 위조 토큰은 뒤따르는 보안 체인이 401로 잘라낸다. 즉 최악의 경우 공격자가 얻는 것은 401 대신 403뿐이다.
 */
public final class JwtClaims {

    private static final String BEARER = "bearer ";

    private JwtClaims() {
    }

    /** JWT가 아니거나 파싱 불가면 null. PAT 원문·불투명 토큰도 여기서 null이 된다. */
    public static JWTClaimsSet parseBearer(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.length() <= BEARER.length()) {
            return null;
        }
        if (!authorizationHeader.substring(0, BEARER.length()).toLowerCase(Locale.ROOT).equals(BEARER)) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER.length()).trim();
        try {
            return JWTParser.parse(token).getJWTClaimsSet();
        } catch (Exception e) {
            return null; // 서명 검증은 보안 체인 몫 — 여기서는 "읽을 수 없음"으로만 다룬다
        }
    }

    /** 토큰 출처. PAT 교환으로 발급된 JWT만 {@code "PAT"}. 세션 JWT는 다른 값이거나 없다. */
    public static String provider(JWTClaimsSet claims) {
        return stringClaim(claims, "provider");
    }

    public static String subject(JWTClaimsSet claims) {
        return claims == null ? null : claims.getSubject();
    }

    /**
     * {@code scope} 클레임. <b>클레임 자체가 없으면 null</b>(구버전 PAT — 스코프 강제 대상이 아니다),
     * 있으면 빈 리스트일 수 있다(= 아무 권한도 없음). 배열과 공백/쉼표 구분 문자열을 모두 받는다.
     */
    public static List<String> scopes(JWTClaimsSet claims) {
        if (claims == null) {
            return null;
        }
        Object raw = claims.getClaim("scope");
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                addIfPresent(out, item == null ? null : item.toString());
            }
            return out;
        }
        if (raw instanceof String text) {
            for (String part : text.split("[\\s,]+")) {
                addIfPresent(out, part);
            }
            return out;
        }
        return null; // 알 수 없는 형태 — 스코프 강제 대상으로 보지 않는다
    }

    private static void addIfPresent(List<String> out, String value) {
        if (value != null && !value.isBlank()) {
            out.add(value.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        if (claims == null) {
            return null;
        }
        Object raw = claims.getClaim(name);
        return raw instanceof String s ? s : null;
    }
}
