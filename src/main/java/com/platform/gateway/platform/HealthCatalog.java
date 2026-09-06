package com.platform.gateway.platform;

import java.util.List;

/**
 * 헬스 집계가 보고하는 컴포넌트 목록 — 순서·한국어 이름·분류·프로브 방식. 주소만 설정
 * ({@link PlatformHealthProperties#getTargets()})이고 나머지는 코드에 둔다: "이 플랫폼이 무엇으로
 * 이루어져 있는가"는 배포 환경마다 달라지는 값이 아니다.
 */
final class HealthCatalog {

    /** 프로브 방식. 응답을 어떻게 상태로 옮기는지가 컴포넌트마다 다르다. */
    enum Probe {
        /** 게이트웨이 자신 — 이 코드가 돌고 있다는 사실이 곧 UP이다. */
        SELF,
        /** Spring Boot Actuator {@code /actuator/health}(+{@code /actuator/info}의 build.version). */
        ACTUATOR,
        /** Actuator가 없을 수도 있는 Spring 앱(eureka) — 404면 루트({@code /}) 200으로 판정. */
        ACTUATOR_OR_ROOT,
        /** 2xx면 UP. Keycloak·MinIO·Loki·Grafana·collaboration-service. */
        PLAIN,
        /** OpenSearch {@code /_cluster/health} — green·yellow는 UP, red는 DEGRADED. */
        OPENSEARCH,
        /** 직접 프로브 없음. Spring 서비스 health의 {@code components.db}를 모아 판정. */
        DERIVED_DB,
        /** 직접 프로브 없음. {@code components.redis}를 모아 판정. */
        DERIVED_REDIS
    }

    record Spec(String id, String name, String group, Probe probe) {}

    static final String GROUP_SERVICE = "service";
    static final String GROUP_INFRA = "infra";

    /** 화면에 나가는 순서 그대로. */
    static final List<Spec> SPECS = List.of(
            new Spec("gateway-server", "게이트웨이", GROUP_SERVICE, Probe.SELF),
            new Spec("auth-server", "인증 서버", GROUP_SERVICE, Probe.ACTUATOR),
            new Spec("org-service", "조직 서비스", GROUP_SERVICE, Probe.ACTUATOR),
            new Spec("wiki-backend", "위키 백엔드", GROUP_SERVICE, Probe.ACTUATOR),
            new Spec("alm-backend", "ALM 백엔드", GROUP_SERVICE, Probe.ACTUATOR),
            new Spec("agent-service", "에이전트 서비스", GROUP_SERVICE, Probe.ACTUATOR),
            new Spec("migration-service", "이관 서비스", GROUP_SERVICE, Probe.ACTUATOR),
            new Spec("board-service", "게시판 서비스", GROUP_SERVICE, Probe.ACTUATOR),
            new Spec("docs-backend", "공개 문서 백엔드", GROUP_SERVICE, Probe.ACTUATOR),
            new Spec("collaboration-service", "공동 편집 서비스", GROUP_SERVICE, Probe.PLAIN),
            new Spec("eureka", "서비스 레지스트리", GROUP_INFRA, Probe.ACTUATOR_OR_ROOT),
            new Spec("keycloak", "Keycloak", GROUP_INFRA, Probe.PLAIN),
            new Spec("postgres", "PostgreSQL", GROUP_INFRA, Probe.DERIVED_DB),
            new Spec("redis", "Redis", GROUP_INFRA, Probe.DERIVED_REDIS),
            new Spec("minio", "MinIO", GROUP_INFRA, Probe.PLAIN),
            new Spec("opensearch", "OpenSearch", GROUP_INFRA, Probe.OPENSEARCH),
            new Spec("loki", "Loki", GROUP_INFRA, Probe.PLAIN),
            new Spec("grafana", "Grafana", GROUP_INFRA, Probe.PLAIN));

    private HealthCatalog() {
    }
}
