package com.platform.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CircuitBreaker {@code fallbackUri: forward:/fallback/<서비스>} 대상 — 다운스트림 불능(연결 실패·응답 타임아웃·
 * 회로 열림) 시 계약된 {@code 503 {"error": 메시지}}. SCG 기본 오류 본문({@code timestamp/path/status/error})이
 * 나가면 프론트의 "503 → 장애 안내" 매핑이 깨진다(P-10).
 *
 * <p>메시지는 공용 오류 계약(common-starter)과 같은 <b>한국어 문장</b>이다 — wiki-front·alm-front는 {@code error}를
 * 그대로 토스트·에러 상태로 띄운다. board만 예외로 기존 코드 {@code board_unavailable}을 유지한다(myFront가
 * 코드로 매핑하는 기존 계약).
 *
 * <p>다운스트림이 <b>직접</b> 낸 5xx(예: org 불능 → 위키 503 {@code {"error": 한국어}})는 여기로 오지 않고
 * 그대로 전달된다 — 라우트 CircuitBreaker에 {@code statusCodes}를 걸지 않았기 때문이다.
 */
@RestController
public class FallbackController {

    /** 라우트 CircuitBreaker 이름(application.yml) → 응답 문구. */
    static final Map<String, String> MESSAGES = Map.of(
            "board", "board_unavailable",
            "wiki", "위키 서비스에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            "alm", "ALM 서비스에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            "org", "조직 서비스에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            "agent", "에이전트 서비스에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            "migration", "이관 서비스에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            "search", "검색 서비스에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    static final String DEFAULT_MESSAGE = "서비스에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<Map<String, String>> fallback(@PathVariable String service) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", MESSAGES.getOrDefault(service, DEFAULT_MESSAGE)));
    }
}
