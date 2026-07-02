package com.platform.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** CircuitBreaker fallbackUri(forward:/fallback/board) 대상 — 다운스트림 불능 시 표준 503. */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/board")
    public ResponseEntity<Map<String, String>> board() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "board_unavailable"));
    }
}
