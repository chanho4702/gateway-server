package com.platform.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("platform-api");

    private Jwt jwt(List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("1");
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }

    @Test
    void acceptsExpectedAudience() {
        assertThat(validator.validate(jwt(List.of("platform-api"))).hasErrors()).isFalse();
    }

    @Test
    void rejectsWrongAudience() {
        assertThat(validator.validate(jwt(List.of("other-api"))).hasErrors()).isTrue();
    }

    @Test
    void rejectsMissingAudience() {
        assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
    }
}
