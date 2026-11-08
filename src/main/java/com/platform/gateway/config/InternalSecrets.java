package com.platform.gateway.config;

/**
 * 내부 비밀 발신값 정규화(N5). {@code AGENT_INTERNAL_SECRET}은 수신 측(auth-server) 허용 목록과 같은 .env 변수라
 * 회전 중에는 {@code 신,구} 콤마 목록이 들어온다. 헤더에 목록째 실으면 수신 측 어떤 항목과도 맞지 않아 403이다.
 * 그래서 콤마로 나눠 trim한 뒤 비어 있지 않은 첫 항목 하나만 보낸다 — auth-server {@code OrgInternalClient.firstToken}과
 * 같은 의미론. 결과가 없으면 빈 문자열(= 비밀 미설정, 기존 fail-closed 동작 유지).
 */
public final class InternalSecrets {

    private InternalSecrets() {
    }

    public static String firstToken(String configured) {
        if (configured == null) return "";
        for (String item : configured.split(",")) {
            String v = item.trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
