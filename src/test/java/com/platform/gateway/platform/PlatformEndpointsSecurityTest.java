package com.platform.gateway.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 API·Actuator의 노출 경계. 게이트웨이에 라우트가 아닌 <b>자기 컨트롤러</b>가 생긴 것은 이번이
 * fallback 다음 두 번째라, "정말 여기서 처리되는가"와 "누가 열리는가"를 실측으로 못박아 둔다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformEndpointsSecurityTest {

    @LocalServerPort
    int port;

    // actuator가 자기 엔드포인트용 매핑을 하나 더 등록하므로 이름으로 못박는다(WebFlux 컨트롤러용).
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** 라우트가 아니라 게이트웨이 자신의 컨트롤러가 잡아야 한다 — 라우트로 새면 502/503이 난다. */
    @Test
    void platform_경로는_게이트웨이_컨트롤러가_처리한다() {
        for (String path : new String[]{"/api/platform/health", "/api/platform/stats/tokens"}) {
            MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
            Object handler = handlerMapping.getHandler(MockServerWebExchange.from(request)).block();
            assertThat(handler).as("%s 핸들러", path).isInstanceOf(HandlerMethod.class);
            assertThat(((HandlerMethod) handler).getBeanType()).isEqualTo(PlatformController.class);
        }
    }

    @Test
    void platform_경로는_로그인_없이는_401이다() {
        client().get().uri("/api/platform/health").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/platform/stats/tokens").exchange().expectStatus().isUnauthorized();
    }

    /**
     * Actuator health/info는 토큰 없이 열린다 — 내부 네트워크 전용이라는 전제 위에 있다(호스트 포트
     * 미발행 + nginx가 /actuator/**를 넘기지 않음). Redis가 없는 CI에서는 503이 정상이므로 401만 본다.
     */
    @Test
    void actuator_health와_info는_토큰_없이_열린다() {
        client().get().uri("/actuator/health").exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
        client().get().uri("/actuator/info").exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
    }

    /** 노출 목록에 없는 엔드포인트는 열려 있으면 안 된다 — 게이트웨이 라우트 덤프가 그중 하나다. */
    @Test
    void 다른_actuator_엔드포인트는_노출하지_않는다() {
        client().get().uri("/actuator/gateway/routes").exchange()
                .expectStatus().value(status -> assertThat(status).isIn(401, 403, 404));
        client().get().uri("/actuator/env").exchange()
                .expectStatus().value(status -> assertThat(status).isIn(401, 403, 404));
    }
}
