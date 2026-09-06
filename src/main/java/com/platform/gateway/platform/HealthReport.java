package com.platform.gateway.platform;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/platform/health} 응답 본문.
 *
 * @param checkedAt        이 결과를 실제로 수집한 시각(캐시 히트여도 수집 시각 그대로 — 화면이 신선도를 판단한다)
 * @param cacheTtlSeconds  다음 수집까지의 캐시 수명. 화면 폴링 주기를 이보다 짧게 잡아도 서비스는 더 맞지 않는다.
 */
public record HealthReport(Instant checkedAt, long cacheTtlSeconds, List<ComponentHealth> components) {

    /** {@code UP} · {@code DEGRADED} · {@code DOWN} · {@code UNKNOWN} 네 가지만 나간다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ComponentHealth(String id, String name, String group, String status,
                                  Long latencyMs, String version, String detail) {}
}
