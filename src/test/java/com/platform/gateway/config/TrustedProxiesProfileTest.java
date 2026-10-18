package com.platform.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trusted-proxies 범위가 프로필별로 맞게 갈리는지(P-23). 예전에는 "로컬 dev 전용" 주석과 달리 base yml에 있어
 * docker에서도 사설대역 전체(10/8 포함)·루프백을 신뢰했다.
 * <ul>
 *   <li>base(프로필 없음): 루프백만 — 같은 호스트의 프록시(vite·로컬 nginx)만 믿는다.</li>
 *   <li>dev: 기존 그대로 루프백 + 사설대역(도커 NAT로 호스트 dev 게이트웨이에 붙는 경우 포함).</li>
 *   <li>docker: 도커 브리지 기본 주소 풀(172.16/12·192.168/16)만, {@code GATEWAY_TRUSTED_PROXIES}로 좁힌다.</li>
 * </ul>
 * SCG는 원격 주소 문자열 전체를 이 정규식으로 판정한다.
 */
class TrustedProxiesProfileTest {

    private static final String KEY = "spring.cloud.gateway.server.webflux.trusted-proxies";

    private static Pattern resolve(String... files) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        // 뒤에 오는 파일(프로필)이 이긴다 — Spring Boot의 프로필 우선순위와 같은 순서로 쌓는다.
        for (String file : files) {
            List<PropertySource<?>> sources = loader.load(file, new ClassPathResource(file));
            for (PropertySource<?> source : sources) {
                env.getPropertySources().addFirst(source);
            }
        }
        String regex = env.getProperty(KEY);
        assertThat(regex).as(String.join("+", files) + "의 " + KEY).isNotBlank();
        return Pattern.compile(regex);
    }

    @Test
    void baseTrustsLoopbackOnly() throws IOException {
        Pattern base = resolve("application.yml");
        assertThat(base.matcher("127.0.0.1").matches()).isTrue();
        assertThat(base.matcher("0:0:0:0:0:0:0:1").matches()).isTrue();
        assertThat(base.matcher("172.22.0.26").matches()).isFalse();
        assertThat(base.matcher("10.0.0.5").matches()).isFalse();
        assertThat(base.matcher("192.168.1.10").matches()).isFalse();
    }

    @Test
    void devKeepsLoopbackAndPrivateRanges() throws IOException {
        Pattern dev = resolve("application.yml", "application-dev.yml");
        assertThat(dev.matcher("127.0.0.1").matches()).isTrue();
        assertThat(dev.matcher("10.0.0.5").matches()).isTrue();
        assertThat(dev.matcher("172.22.0.26").matches()).isTrue();
        assertThat(dev.matcher("192.168.65.1").matches()).isTrue();
        assertThat(dev.matcher("8.8.8.8").matches()).isFalse();
    }

    /** 현재 배포의 nginx(platform_default 172.22.0.26 실측)는 계속 신뢰돼야 한다 — 아니면 OIDC redirect_uri가 깨진다. */
    @Test
    void dockerTrustsDockerBridgePoolsOnly() throws IOException {
        Pattern docker = resolve("application.yml", "application-docker.yml");
        assertThat(docker.matcher("172.22.0.26").matches()).isTrue();
        assertThat(docker.matcher("172.17.0.2").matches()).isTrue();
        assertThat(docker.matcher("172.31.255.1").matches()).isTrue();
        assertThat(docker.matcher("192.168.16.3").matches()).isTrue();
        assertThat(docker.matcher("127.0.0.1").matches()).isFalse();
        assertThat(docker.matcher("10.0.0.5").matches()).isFalse();
        assertThat(docker.matcher("172.32.0.1").matches()).isFalse();
        assertThat(docker.matcher("172.2.0.1").matches()).isFalse();
        assertThat(docker.matcher("8.8.8.8").matches()).isFalse();
    }
}
