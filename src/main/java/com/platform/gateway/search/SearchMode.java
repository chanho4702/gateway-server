package com.platform.gateway.search;

import java.util.Locale;
import java.util.Set;

/**
 * 통합 검색 설치 옵션 {@code SEARCH_MODE} — 이 플랫폼에서 검색을 누가 서비스하는가.
 *
 * <p>"끈다"는 <b>검색 없음이 아니라 라이트 검색</b>이다. wiki-backend가 search-service와 <b>같은
 * GraphQL 계약</b>을 Postgres {@code pg_trgm}으로 구현하고 있어, 라우트 대상만 바꾸면 프론트는
 * 무수정으로 동작한다. 그래서 이 옵션이 켜고 끄는 것은 통합(cross-app)·형태소 분석 검색이지
 * 검색 자체가 아니다(설계 §1).
 *
 * <table>
 *   <caption>모드 → 라우트 대상 · 헬스 표</caption>
 *   <tr><th>모드</th><th>{@code /api/search/**}</th><th>헬스 표에서 빠지는 행</th></tr>
 *   <tr><td>{@code lite}(기본)</td><td>{@code WIKI_SERVICE_URI}(없으면 {@code lb://wiki-backend})</td>
 *       <td>{@code search-service}·{@code opensearch}</td></tr>
 *   <tr><td>{@code opensearch}</td><td>{@code http://search-service:9140}</td><td>없음</td></tr>
 *   <tr><td>{@code external}</td><td>{@code http://search-service:9140}</td>
 *       <td>{@code opensearch}(고객사 것이라 우리가 프로브하지 않는다)</td></tr>
 * </table>
 *
 * <p><b>스위치는 하나다.</b> 라우트 대상·헬스 프로브 행·프론트 기능 플래그를 각각 손으로 맞추게 하면
 * 반쪽만 바뀐 배포가 생긴다 — 셋 다 이 값 하나에서 파생한다(설계 §2.1 A안).
 * 예외는 {@code SEARCH_SERVICE_URI} 하나뿐이고, 그것은 <b>명시하면 이기는 탈출구</b>다.
 */
public enum SearchMode {

    /** 위키 자체 검색(추가 컨테이너 0). 신규 설치의 기본값. */
    LITE,
    /** search-service + 우리가 띄운 OpenSearch. 한국어 형태소 + 통합 검색. */
    OPENSEARCH,
    /** search-service만. OpenSearch는 고객사가 이미 굴리는 클러스터({@code OPENSEARCH_URI}). */
    EXTERNAL;

    /** 모르는 값·빈 값이 오면 여기로 내려앉는다 — 기동을 막지 않는다. */
    public static final SearchMode DEFAULT = LITE;

    /** 비-lite 모드의 라우트 기본 대상. 컨테이너 네트워크의 DNS 이름이다. */
    static final String DEFAULT_SEARCH_SERVICE_URI = "http://search-service:9140";

    /** lite 모드의 라우트 기본 대상. 위키는 인스턴스가 여럿일 수 있어 디스커버리를 탄다. */
    static final String DEFAULT_WIKI_SERVICE_URI = "lb://wiki-backend";

    /** 검색을 끄면 헬스 표에서 사라져야 하는 컴포넌트 id({@code HealthCatalog}와 같은 이름). */
    private static final String SEARCH_SERVICE_COMPONENT = "search-service";
    private static final String OPENSEARCH_COMPONENT = "opensearch";
    private static final Set<String> SEARCH_COMPONENTS =
            Set.of(SEARCH_SERVICE_COMPONENT, OPENSEARCH_COMPONENT);

    /**
     * 설정값 → 모드. 대소문자·앞뒤 공백을 무시하고, <b>모르는 값이면 {@code null}</b>을 준다.
     * 기동을 막을지 경고하고 기본값으로 갈지는 부르는 쪽(설정 파생)이 정한다.
     */
    public static SearchMode parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (SearchMode mode : values()) {
            if (mode.id().equals(normalized)) {
                return mode;
            }
        }
        return null;
    }

    /** 설정·API에 나가는 소문자 이름({@code lite}·{@code opensearch}·{@code external}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 통합(cross-app) 검색이 가능한 모드인가. lite에서는 각 앱이 자기 것만 찾는다(설계 §7). */
    public boolean unified() {
        return this != LITE;
    }

    /** 재색인이라는 개념이 있는 모드인가. lite는 색인이 없어 {@code /admin/reindex}가 404다. */
    public boolean reindex() {
        return this != LITE;
    }

    /**
     * {@code /api/search/**}의 라우트 대상.
     *
     * @param searchServiceUri {@code SEARCH_SERVICE_URI} — <b>명시하면 모드와 무관하게 이긴다</b>(탈출구)
     * @param wikiServiceUri   {@code WIKI_SERVICE_URI} — lite 모드에서만 쓰인다(없으면 디스커버리)
     */
    public String routeUri(String searchServiceUri, String wikiServiceUri) {
        if (hasText(searchServiceUri)) {
            return searchServiceUri.trim();
        }
        if (this == LITE) {
            return hasText(wikiServiceUri) ? wikiServiceUri.trim() : DEFAULT_WIKI_SERVICE_URI;
        }
        return DEFAULT_SEARCH_SERVICE_URI;
    }

    /**
     * 이 모드에서 헬스 표에 남는 컴포넌트인가. 없는 것을 프로브해 항상 DOWN인 행을 띄우면 대시보드가
     * 거짓말을 하게 된다 — 주소를 비워 행을 지우는 기존 규칙과 같은 취지다.
     */
    public boolean probesComponent(String componentId) {
        return switch (this) {
            // 두 컨테이너 모두 없다.
            case LITE -> !SEARCH_COMPONENTS.contains(componentId);
            // search-service는 우리 것, OpenSearch는 고객사 것 — 남의 클러스터를 우리 표에 올리지 않는다.
            case EXTERNAL -> !OPENSEARCH_COMPONENT.equals(componentId);
            case OPENSEARCH -> true;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
