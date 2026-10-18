package com.platform.gateway.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * 라우트 경유 테스트용 — 어떤 Bearer든 유효 JWT로 받아들이는 디코더. JWKS(auth-server) 없이 보안 체인을
 * 통과시켜 "그 뒤 라우팅·회복탄력성"만 본다. 서명 검증 자체는 SecurityConfigTest·AudienceValidatorTest 몫이다.
 */
@TestConfiguration
public class AcceptAnyJwtConfig {

    @Bean
    @Primary
    ReactiveJwtDecoder acceptAnyJwtDecoder() {
        return token -> Mono.just(Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject("member-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
    }
}
