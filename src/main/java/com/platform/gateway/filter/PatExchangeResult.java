package com.platform.gateway.filter;

/**
 * auth-server {@code /internal/pat/exchange} 호출 결과. 세 갈래로만 갈라진다 —
 * "토큰이 틀렸다"(401)와 "auth-server를 못 믿겠다"(503)를 절대 섞지 않기 위해서다.
 * 섞으면 auth-server 장애가 사용자에게 "토큰이 잘못됐다"로 둔갑하고, 반대로 하면 부정 토큰이 통과한다.
 */
public sealed interface PatExchangeResult {

    /** 교환 성공 — 플랫폼 JWT와 그 수명(초). */
    record Success(String accessToken, long expiresInSeconds) implements PatExchangeResult {}

    /** 토큰이 없음·만료·폐기 — auth-server가 401로 단정한 경우에만. */
    record Invalid() implements PatExchangeResult {}

    /** auth-server 불능(5xx·타임아웃·연결 실패·비밀 불일치 403). 사유는 로그·진단용. */
    record Unavailable(String reason) implements PatExchangeResult {}
}
