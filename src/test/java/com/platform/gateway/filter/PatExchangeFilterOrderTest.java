package com.platform.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필터 순서 회귀 가드. PAT 교환이 성립하는 유일한 조건은 "보안 체인보다 먼저 도는 것"이다 —
 * 누가 @Order를 지우거나 GlobalFilter로 되돌리면 PAT 요청이 헤더 치환 전에 401로 잘린다.
 * 스프링이 주입하는 List<WebFilter>는 order 순으로 정렬되므로 인덱스로 실측한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PatExchangeFilterOrderTest {

    @LocalServerPort
    int port;

    @Autowired
    List<WebFilter> webFilters;

    @Test
    void patFilterRunsBeforeSecurityChain() {
        int patIndex = indexOf(PatExchangeWebFilter.class);
        int securityIndex = indexOf(WebFilterChainProxy.class);

        assertThat(patIndex).as("PatExchangeWebFilter가 WebFilter 빈으로 등록돼야 한다").isNotNegative();
        assertThat(securityIndex).as("WebFilterChainProxy(보안 체인)").isNotNegative();
        assertThat(patIndex).as("PAT 필터가 보안 체인보다 먼저여야 한다").isLessThan(securityIndex);
    }

    /**
     * 기본 프로필에는 AGENT_INTERNAL_SECRET이 없다 → PAT는 전부 401 invalid_token.
     * 필터가 보안 체인보다 뒤에 있었다면 Security가 먼저 잡아 본문 없는 401이 나온다 —
     * 본문의 {@code invalid_token}이 "우리 필터가 먼저 돌았다"는 증거다.
     */
    @Test
    void patRequestIsRejectedByFilterNotBySecurity() {
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build()
                .get().uri("/api/me")
                .header("Authorization", "Bearer chanho_pat_" + "A".repeat(43))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("invalid_token");
    }

    private int indexOf(Class<?> type) {
        for (int i = 0; i < webFilters.size(); i++) {
            if (type.isInstance(webFilters.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
