package com.platform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 다운스트림 무한대기 방지 — 전역 connect/response 타임아웃이 바인딩되는지. */
@SpringBootTest
class HttpClientTimeoutTest {

    @Autowired HttpClientProperties httpClientProperties;

    @Test
    void globalTimeoutsAreConfigured() {
        assertThat(httpClientProperties.getConnectTimeout()).isEqualTo(3000);
        assertThat(httpClientProperties.getResponseTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
