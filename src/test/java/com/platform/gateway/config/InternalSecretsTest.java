package com.platform.gateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 내부 비밀 env가 회전용 콤마 목록({@code 신,구})이어도 발신은 첫 항목 하나만 싣는다(N5). */
class InternalSecretsTest {

    @Test
    void 콤마_목록이면_첫_항목() {
        assertThat(InternalSecrets.firstToken("a,b")).isEqualTo("a");
    }

    @Test
    void 빈_항목은_건너뛰고_trim한다() {
        assertThat(InternalSecrets.firstToken(" ,b ")).isEqualTo("b");
        assertThat(InternalSecrets.firstToken("  s1  ")).isEqualTo("s1");
    }

    @Test
    void 비거나_null이거나_콤마뿐이면_빈_값() {
        assertThat(InternalSecrets.firstToken(null)).isEmpty();
        assertThat(InternalSecrets.firstToken("")).isEmpty();
        assertThat(InternalSecrets.firstToken(" , ,")).isEmpty();
    }
}
