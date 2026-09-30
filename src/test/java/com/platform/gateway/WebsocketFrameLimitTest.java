package com.platform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공동 편집 WebSocket 프레임 상한 — 기본 64KiB면 큰 문서의 첫 동기화 프레임이 끊긴다.
 * 프로퍼티 바인딩뿐 아니라 실제로 쓰이는 두 빈(브라우저→게이트웨이 서버 측,
 * 게이트웨이→서비스 클라이언트 측)의 spec에 반영됐는지까지 확인한다.
 */
@SpringBootTest
class WebsocketFrameLimitTest {

    private static final int SIXTEEN_MIB = 16 * 1024 * 1024;

    @Autowired HttpClientProperties httpClientProperties;
    @Autowired ReactorNettyRequestUpgradeStrategy upgradeStrategy;
    @Autowired ReactorNettyWebSocketClient webSocketClient;

    @Test
    void propertyIsBound() {
        assertThat(httpClientProperties.getWebsocket().getMaxFramePayloadLength()).isEqualTo(SIXTEEN_MIB);
    }

    @Test
    void serverSideUpgradeUsesRaisedLimit() {
        assertThat(upgradeStrategy.getWebsocketServerSpec().maxFramePayloadLength()).isEqualTo(SIXTEEN_MIB);
    }

    @Test
    void clientSideUsesRaisedLimit() {
        assertThat(webSocketClient.getWebsocketClientSpec().maxFramePayloadLength()).isEqualTo(SIXTEEN_MIB);
    }
}
