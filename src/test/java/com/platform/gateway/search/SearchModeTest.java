package com.platform.gateway.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설치 옵션의 계산 규칙. 이 표가 곧 계약이라 리액터·스프링 없이 읽고 시험할 수 있어야 한다
 * (게이트웨이가 이 값에서 라우트 대상·헬스 행·프론트 기능 플래그를 전부 파생한다).
 */
class SearchModeTest {

    // --- 값 해석 ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"lite", "LITE", "Lite", "  lite  "})
    void 대소문자와_공백을_무시한다(String raw) {
        assertThat(SearchMode.parse(raw)).isEqualTo(SearchMode.LITE);
    }

    @Test
    void 세_모드를_모두_해석한다() {
        assertThat(SearchMode.parse("lite")).isEqualTo(SearchMode.LITE);
        assertThat(SearchMode.parse("opensearch")).isEqualTo(SearchMode.OPENSEARCH);
        assertThat(SearchMode.parse("external")).isEqualTo(SearchMode.EXTERNAL);
    }

    /** 모르는 값은 null — 기동을 막을지 경고하고 기본값으로 갈지는 부르는 쪽이 정한다. */
    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "elasticsearch", "on", "true", "opensearch2"})
    void 모르는_값은_해석되지_않는다(String raw) {
        assertThat(SearchMode.parse(raw)).isNull();
    }

    @Test
    void null도_해석되지_않는다() {
        assertThat(SearchMode.parse(null)).isNull();
    }

    @Test
    void 기본값은_lite다() {
        assertThat(SearchMode.DEFAULT).isEqualTo(SearchMode.LITE);
    }

    // --- 라우트 대상 -----------------------------------------------------------

    @Test
    void lite는_위키로_간다() {
        assertThat(SearchMode.LITE.routeUri(null, null)).isEqualTo("lb://wiki-backend");
        assertThat(SearchMode.LITE.routeUri("", "  ")).isEqualTo("lb://wiki-backend");
    }

    /** docker처럼 WIKI_SERVICE_URI를 명시한 배포는 그 주소를 그대로 쓴다(라우트 wiki와 같은 대상). */
    @Test
    void lite에_WIKI_SERVICE_URI가_있으면_그것을_쓴다() {
        assertThat(SearchMode.LITE.routeUri(null, "http://wiki-backend:9110"))
                .isEqualTo("http://wiki-backend:9110");
    }

    @Test
    void opensearch와_external은_search_service로_간다() {
        assertThat(SearchMode.OPENSEARCH.routeUri(null, "http://wiki-backend:9110"))
                .isEqualTo("http://search-service:9140");
        assertThat(SearchMode.EXTERNAL.routeUri(null, null))
                .isEqualTo("http://search-service:9140");
    }

    /** 탈출구 — 명시된 SEARCH_SERVICE_URI는 모드와 무관하게 이긴다. */
    @Test
    void SEARCH_SERVICE_URI가_명시되면_모든_모드에서_이긴다() {
        for (SearchMode mode : SearchMode.values()) {
            assertThat(mode.routeUri(" http://search.internal:9200 ", "http://wiki-backend:9110"))
                    .as("%s 모드", mode)
                    .isEqualTo("http://search.internal:9200");
        }
    }

    // --- 능력 플래그 -----------------------------------------------------------

    /** 통합 검색·재색인은 lite에 없는 능력이다 — 프론트가 이 두 값으로 메뉴를 지운다. */
    @Test
    void lite에는_통합검색도_재색인도_없다() {
        assertThat(SearchMode.LITE.unified()).isFalse();
        assertThat(SearchMode.LITE.reindex()).isFalse();
        for (SearchMode mode : new SearchMode[]{SearchMode.OPENSEARCH, SearchMode.EXTERNAL}) {
            assertThat(mode.unified()).as("%s unified", mode).isTrue();
            assertThat(mode.reindex()).as("%s reindex", mode).isTrue();
        }
    }

    @Test
    void id는_소문자다() {
        assertThat(SearchMode.OPENSEARCH.id()).isEqualTo("opensearch");
    }

    // --- 헬스 표 --------------------------------------------------------------

    @Test
    void lite는_검색_두_행을_모두_지운다() {
        assertThat(SearchMode.LITE.probesComponent("search-service")).isFalse();
        assertThat(SearchMode.LITE.probesComponent("opensearch")).isFalse();
        assertThat(SearchMode.LITE.probesComponent("wiki-backend")).isTrue();
    }

    @Test
    void external은_opensearch_행만_지운다() {
        assertThat(SearchMode.EXTERNAL.probesComponent("search-service")).isTrue();
        assertThat(SearchMode.EXTERNAL.probesComponent("opensearch")).isFalse();
    }

    @Test
    void opensearch는_아무_행도_지우지_않는다() {
        assertThat(SearchMode.OPENSEARCH.probesComponent("search-service")).isTrue();
        assertThat(SearchMode.OPENSEARCH.probesComponent("opensearch")).isTrue();
    }
}
