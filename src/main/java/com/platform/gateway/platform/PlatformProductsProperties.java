package com.platform.gateway.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code platform.products} — 이 설치에 들어 있는 제품 목록(env {@code PLATFORM_PRODUCTS}, 콤마 목록,
 * 기본 {@code wiki,alm,board,agent}).
 *
 * <p>지금 읽는 곳은 기능 플래그 API({@code GET /api/platform/features}의 {@code products}) 하나다 —
 * 위키 스마트 링크가 ALM 설치 여부를 보고 이슈 카드 조회를 할지 정한다. 이 값은 K1(제품 분리 설치)의
 * 씨앗이며, 라우트 404·compose 프로필로 넓히는 것은 K1에서 한다(여기서는 노출만).
 *
 * <p>입력은 관대하게 읽는다 — 공백·대소문자 무시, 모르는 이름은 버리고 경고 한 줄. 값이 아예 비어
 * 있으면(빈 env) 기본값(전부 설치)으로 본다: 빈 문자열 하나로 모든 제품이 조용히 꺼지는 것을 막는다.
 */
@Component
@ConfigurationProperties(prefix = "platform")
public class PlatformProductsProperties {

    private static final Logger log = LoggerFactory.getLogger(PlatformProductsProperties.class);

    /** 계약상 제품 키. 순서가 곧 응답 JSON의 순서다. */
    public static final List<String> KNOWN = List.of("wiki", "alm", "board", "agent");

    private Map<String, Boolean> installed = resolve(KNOWN);

    /** Spring이 콤마 문자열을 목록으로 쪼개 넘긴다. yml 목록 형식도 같은 경로로 들어온다. */
    public void setProducts(List<String> products) {
        this.installed = resolve(products);
    }

    /** {@code wiki/alm/board/agent} → 설치 여부. 네 키가 항상 다 있다. */
    public Map<String, Boolean> getInstalled() {
        return installed;
    }

    static Map<String, Boolean> resolve(List<String> raw) {
        Set<String> picked = new java.util.HashSet<>();
        List<String> unknown = new java.util.ArrayList<>();
        if (raw != null) {
            for (String item : raw) {
                if (item == null) continue;
                String name = item.trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty()) continue;
                if (KNOWN.contains(name)) picked.add(name);
                else unknown.add(item.trim());
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("platform.products(PLATFORM_PRODUCTS)에 모르는 제품 이름 {} — 무시한다. 허용: {}", unknown, KNOWN);
        }
        if (picked.isEmpty() && unknown.isEmpty()) {
            // 빈 값 — 설정 누락으로 보고 기본값(전부)을 쓴다.
            picked.addAll(KNOWN);
        }
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String k : KNOWN) result.put(k, picked.contains(k));
        return Collections.unmodifiableMap(result);
    }
}
