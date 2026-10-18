package com.platform.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * CircuitBreaker {@code fallbackUri: forward:/fallback/<서비스>} 대상 — 다운스트림 불능(연결 실패·응답 타임아웃·
 * 회로 열림) 시 계약된 {@code 503 {"error": "<서비스>_unavailable"}}. SCG 기본 오류 본문
 * ({@code timestamp/path/status/error})이 나가면 프론트의 "503 → 장애 안내" 매핑이 깨진다(P-10).
 *
 * <p>다운스트림이 <b>직접</b> 낸 5xx(예: org 불능 → 위키 503 {@code {"error": 한국어}})는 여기로 오지 않고
 * 그대로 전달된다 — 라우트 CircuitBreaker에 {@code statusCodes}를 걸지 않았기 때문이다.
 */
@RestController
public class FallbackController {

    /** 라우트 CircuitBreaker 이름과 같다(application.yml). 모르는 값은 서비스명을 싣지 않는다. */
    static final Set<String> SERVICES = Set.of("board", "wiki", "alm", "org", "agent", "migration", "search");

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<Map<String, String>> fallback(@PathVariable String service) {
        String error = SERVICES.contains(service) ? service + "_unavailable" : "service_unavailable";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }
}
