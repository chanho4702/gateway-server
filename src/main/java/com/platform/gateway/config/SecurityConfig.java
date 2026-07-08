package com.platform.gateway.config;

import com.platform.gateway.security.AudienceValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * JWT 조기차단(1차 방어). 다운스트림 서비스의 자체 검증(최종 방어)을 대체하지 않는다 — zero-trust 이중 검증.
 * 경로 정책은 board-service SecurityConfig와 동기 유지할 것(여기가 더 좁으면 게이트웨이가 먼저 401).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, CorsConfigurationSource corsSource) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                // Security 레벨 CORS — 401 응답에도 CORS 헤더가 붙는다(프론트 401→refresh 흐름 필수).
                .cors(cors -> cors.configurationSource(corsSource))
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS).permitAll() // preflight
                        .pathMatchers("/oauth2/**", "/login/**", "/api/auth/**",
                                "/.well-known/**", "/fallback/**").permitAll()
                        //인증 관련 경로 + /fallback/** 허용 — fallback을 안 열어두면 서킷브레이커가 forward한 내부 요청이 401로 죽는 미묘한 버그가 생김
                        .pathMatchers(HttpMethod.GET, "/api/board/posts/**").permitAll() // 비 로그인 게시글 열람
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /** auth-server JWKS + issuer/audience 검증 — board-service 디코더와 동일 계약. */
    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(@Value("${platform.jwks-uri}") String jwksUri,
                                          @Value("${platform.issuer}") String issuer,
                                          @Value("${platform.audience}") String audience) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(audience)));
        return decoder;
    }
    //  auth-server의 JWKS 엔드포인트에서 공개키를 받아 서명을 검증하고,
    //  기본 검증(만료+issuer)에 AudienceValidator를 체인으로 추가합니다.
    //  AudienceValidator.java가 필요한 이유: 서명이 유효해도 그 토큰이 이 API를 위해 발급된 것인지는 aud 클레임으로 확인해야 합니다.
    //  같은 IdP가 여러 서비스용 토큰을 발급할 때, A 서비스용 토큰으로 B 서비스를 호출하는 토큰 혼용(confused deputy)을 막는 장치입니다.

    /** CORS 단일 소스 — yml globalcors 대신 이 빈. credentials와 함께 쓰므로 오리진은 구체 값('*' 금지). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${platform.cors-allowed-origin}") String allowedOrigin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L); // preflight 캐시(초)
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
