package com.platform.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.util.List;

/**
 * 테스트용 JWT 발급기. 서명 유효성은 게이트웨이 보안 체인(JWKS)이 볼 일이고, 스코프 필터·관리자 게이트는
 * 클레임만 읽는다 — 그래도 <b>진짜 JWT 형태</b>여야 파싱 경로가 실제와 같아지므로 HMAC으로 서명한다.
 */
public final class TestJwt {

    private static final byte[] KEY = "gateway-unit-test-signing-secret-key-32b".getBytes();

    private TestJwt() {
    }

    /** 브라우저 세션 JWT — {@code provider}·{@code scope} 클레임이 없다. */
    public static String session(String subject) {
        return of(subject, null, null);
    }

    /** 교환된 개인 API 토큰 — {@code provider=PAT} + 스코프 배열. */
    public static String pat(String subject, List<String> scopes) {
        return of(subject, "PAT", scopes);
    }

    public static String of(String subject, String provider, List<String> scopes) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(subject);
        if (provider != null) {
            claims.claim("provider", provider);
        }
        if (scopes != null) {
            claims.claim("scope", scopes);
        }
        try {
            SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            token.sign(new MACSigner(KEY));
            return token.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 JWT 서명 실패", e);
        }
    }
}
