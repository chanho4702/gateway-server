package com.platform.gateway.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import com.platform.gateway.security.AcceptAnyJwtConfig;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * board 외 제품 라우트도 다운스트림 불능 시 계약된 {@code 503 {"error": 메시지}}로
 * 떨어지는지(P-10) — 문구는 한국어(board만 기존 코드). 이전에는 SCG 기본 오류 본문({@code timestamp/path/status/error})이 나가
 * 프론트의 "503 → 장애 안내" 매핑이 깨졌다. 닫힌 포트 → connection refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "WIKI_SERVICE_URI=http://localhost:59991",
        "ALM_SERVICE_URI=http://localhost:59992",
        "ORG_SERVICE_URI=http://localhost:59993",
        "AGENT_SERVICE_URI=http://localhost:59994",
        "MIGRATION_SERVICE_URI=http://localhost:59995",
        // lite 검색은 WIKI_SERVICE_URI로 간다 — 위 닫힌 포트
        "SEARCH_MODE=lite"})
@Import(AcceptAnyJwtConfig.class)
class ServiceFallbackTest {

    @LocalServerPort
    int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** 라우트 경유 — 인증된 요청이 죽은 다운스트림을 만나면 SCG 기본 본문이 아니라 계약 503. */
    @ParameterizedTest
    @CsvSource({
            "GET,/api/wiki/spaces,wiki",
            "POST,/api/wiki/pages,wiki",
            "GET,/api/alm/projects,alm",
            "GET,/api/org/members,org",
            "PATCH,/api/org/members/1,org",
            "GET,/api/agent/personas,agent",
            "GET,/api/migration/jobs,migration",
            "POST,/api/search/graphql,search"})
    void downstreamDownReturnsContract503(String method, String path, String service) {
        client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header("Authorization", "Bearer any")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").isEqualTo(FallbackController.MESSAGES.get(service))
                .jsonPath("$.timestamp").doesNotExist();
    }

    /** fallback 엔드포인트 자체 — 서킷브레이커가 forward하는 내부 대상이라 permitAll이다. */
    @ParameterizedTest
    @CsvSource({"wiki", "alm", "org", "agent", "migration", "search"})
    void fallbackEndpointReturnsKoreanContract503(String service) {
        client().get().uri("/fallback/" + service).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody().jsonPath("$.error").value(org.hamcrest.Matchers.containsString("일시적으로 연결할 수 없습니다"));
    }

    /** board는 myFront가 코드로 매핑하는 기존 계약 그대로. */
    @Test
    void boardFallbackKeepsLegacyCode() {
        client().get().uri("/fallback/board").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("board_unavailable");
    }

    @Test
    void unknownFallbackIsGeneric503() {
        client().post().uri("/fallback/nope").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo(FallbackController.DEFAULT_MESSAGE);
    }
}
