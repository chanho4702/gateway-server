package com.platform.gateway.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code platform.health.*} — 헬스 집계 설정.
 *
 * <p>{@code targets}는 <b>컴포넌트 id → 프로브 URL</b> 맵이다. 기본값은 application.yml에 있고 각 항목이
 * 자기 환경변수를 갖는다({@code HEALTH_URL_WIKI_BACKEND} 등). 값을 비우면 그 컴포넌트는 아예 보고하지
 * 않는다 — OpenSearch를 내린 배포에서 항상 DOWN인 행이 뜨지 않게 하기 위한 스위치다.
 *
 * <p>id는 {@link HealthCatalog}에 있는 것만 쓰인다. 목록에 없는 id를 넣으면 무시된다 — 이름·분류·프로브
 * 방식이 코드에 있어야 하기 때문이다(설정으로 늘릴 수 있는 것은 주소뿐).
 */
@Component
@ConfigurationProperties(prefix = "platform.health")
public class PlatformHealthProperties {

    private Map<String, String> targets = new LinkedHashMap<>();

    /** 프로브 하나의 상한. health와 info를 병렬로 받으므로 컴포넌트 총 소요도 이 값을 넘지 않는다. */
    private Duration probeTimeout = Duration.ofSeconds(3);

    /**
     * 집계 전체의 상한. 넘기면 그때까지 답한 것만으로 표를 만들고 못 받은 행은 UNKNOWN이 된다 —
     * 느린 컴포넌트 하나가 대시보드 전체를 잡아두지 않게 하는 안전판이다.
     */
    private Duration aggregateTimeout = Duration.ofSeconds(5);

    /** 집계 결과 캐시. 화면을 몇 명이 보든 서비스가 받는 프로브는 이 주기당 1회다. */
    private Duration cacheTtl = Duration.ofSeconds(20);

    public Map<String, String> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, String> targets) {
        this.targets = targets == null ? new LinkedHashMap<>() : targets;
    }

    public Duration getProbeTimeout() {
        return probeTimeout;
    }

    public void setProbeTimeout(Duration probeTimeout) {
        this.probeTimeout = probeTimeout;
    }

    public Duration getAggregateTimeout() {
        return aggregateTimeout;
    }

    public void setAggregateTimeout(Duration aggregateTimeout) {
        this.aggregateTimeout = aggregateTimeout;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }
}
