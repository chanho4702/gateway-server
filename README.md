# gateway-server

모든 클라이언트 트래픽의 **단일 진입점(Single Entry Point)**.
라우팅 + CORS 중앙화 + 요청 로깅(traceId) + **rate limiting + 타임아웃/서킷브레이커 + JWT 조기차단**을 담당한다.

> **이중 검증 원칙**: 게이트웨이의 JWT 검증은 **1차 방어**(쓰레기 트래픽 조기 401)일 뿐, 각 다운스트림 서비스의 자체 JWKS 검증(**최종 방어**)을 대체하지 않는다 — zero-trust.

> 별도 git repo: `github.com/chanho4702/gateway-server` (브랜치 `main`). 우산 repo(MSA_TEMPLATE)에서는 gitignore 됨.

---

## 역할 / 아키텍처

```
  myFront(:5173) ──▶ gateway-server(:8000) ──┬──▶ auth-server(:9000)   JWT발급·JWKS·/api/me
   (base URL 1개)    Spring Cloud Gateway     │
                     라우팅 / CORS / 로깅      └──▶ board-service(:9100) /api/board/**
                     rate limit / CB / JWT 1차검증
                     infra: Keycloak(:8080) · Postgres(:5433) · Redis(:6379)
```

- **CORS 중앙화** — 브라우저(myFront:5173)의 모든 요청이 게이트웨이를 통과하므로 CORS는 여기 한 곳에서만 처리한다. auth-server와 board-service는 CORS를 설정하지 않는다.
- **JWT 조기차단(1차 방어)** — 보호 경로(`POST /api/board/**`, `/api/me` 등)의 무토큰/위조/만료 토큰을 다운스트림에 닿기 전에 401로 차단. JWKS + issuer + audience 검증(board-service와 동일 계약). 공개 경로(`GET /api/board/posts/**`, OIDC 흐름, `/api/auth/**`, JWKS)는 통과. **경로 정책은 board-service SecurityConfig와 동기 유지할 것.** 유효 토큰의 `Authorization` 헤더는 그대로 다운스트림에 전달된다.
- **Rate limiting** — 인증 경로 IP 기준(`/api/auth/**` 5 req/s burst 10, `/oauth2/**`·`/login/**` 10/20). Redis 백엔드, **Redis 부재 시 fail-open**(요청 통과)이라 dev에서 Redis 없이도 동작 — 단 이때 해당 경로 요청마다 `Error calling rate limiter lua` ERROR 로그가 남는 것은 정상. IP 키는 게이트웨이가 클라이언트에 직접 노출되는 전제 — LB/프록시 뒤에 두면 `XForwardedRemoteAddressResolver` 기반 KeyResolver로 교체할 것.
- **회복탄력성** — 전역 connect 3s/response 10s 타임아웃. board 라우트에 CircuitBreaker(+멱등 GET 한정 Retry 2회) → 불능 시 `/fallback/board` 503 `{"error":"board_unavailable"}`.
- **요청 로깅 / traceId** — `GlobalFilter`가 매 요청의 `X-Request-Id`를 보장(없거나 형식 불량이면 UUID 재발급)하고 다운스트림으로 전파한 뒤 `METHOD 경로 (requestId)` 한 줄을 로깅한다.
- **경로 불변(No StripPrefix)** — 클라이언트가 보낸 경로 = 서비스가 받는 경로. 서비스 입장에서 게이트웨이 유무에 따라 경로가 달라지지 않는다.

**요청 처리 순서:** Security(CORS 프리플라이트 응답·JWT 검증·401 조기차단) → `RequestLoggingFilter`(`HIGHEST_PRECEDENCE` GlobalFilter — 401로 잘린 요청은 여기 안 옴) → 라우트 매칭(rate limit·CB 필터) → 다운스트림 프록시.

## 기술 스택

Spring Cloud Gateway **WebFlux** · Spring Boot 4.0.6 · Java 24 · Spring Cloud BOM **2025.1.2** · Gradle

의존성: `spring-cloud-starter-gateway-server-webflux` + `spring-boot-starter-data-redis-reactive`(rate limit) + `spring-cloud-starter-circuitbreaker-reactor-resilience4j`(CB) + `spring-boot-starter-security`/`oauth2-resource-server`(JWT 조기차단). 테스트에만 `spring-boot-starter-webflux`(WebTestClient) 추가.

> Boot 4 / Spring Cloud 2025.x 기준 설정 prefix는 `spring.cloud.gateway.server.webflux.*` 다. 구버전 문서의 `spring.cloud.gateway.routes` 예시를 그대로 붙여넣으면 라우트가 조용히 무시되니 주의.

---

## 빠른 시작

**전제:** auth-server(:9000) · board-service(:9100) · infra(Keycloak+Postgres) 가 먼저 떠 있어야 한다. (게이트웨이 자체는 다운스트림 없이도 뜨지만, 프록시 요청은 연결 실패로 5xx가 난다.)

```powershell
# Windows PowerShell — JDK 24 필요(기본 JDK가 11이면 실패)
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
.\gradlew.bat bootRun        # :8000 기동
```

기동 확인: `http://localhost:8000/.well-known/jwks.json` → auth-server JWKS 그대로 반환.

> 포트 맵: gateway 8000 / auth 9000 / board 9100 / Keycloak 8080 / Postgres 5433 / myFront 5173.

---

## 라우팅 규칙

`application.yml`에 정적으로 선언. **No StripPrefix — 클라이언트가 보낸 경로 = 서비스가 받는 경로.**

| 라우트 id | 경로 패턴 | → 대상 | 비고 |
|---|---|---|---|
| `board` | `/api/board/**` | board-service `:9100` | 게시판·댓글 |
| `auth-oauth2` | `/oauth2/**` | auth-server `:9000` | OIDC 로그인 시작·콜백 |
| `auth-login` | `/login/**` | auth-server `:9000` | OIDC 리다이렉트 흐름 |
| `auth-api` | `/api/auth/**` | auth-server `:9000` | refresh · logout |
| `auth-jwks` | `/.well-known/**` | auth-server `:9000` | JWKS 공개키 |
| `auth-me` | `/api/me` | auth-server `:9000` | 자체 JWT 기반 사용자 정보 |

라우트 id는 `RouteConfigTest`가 검증하므로, id를 바꾸면 테스트도 함께 갱신한다.

> **새 서비스 추가**: `application.yml` 라우트 한 줄 + 환경변수 하나 추가로 복제 완료. (선언 예시는 기존 `board` 라우트 참고.)

---

## 환경 변수

| 변수 | 기본값 (로컬 실행) | Docker Compose 예시 |
|---|---|---|
| `AUTH_SERVER_URI` | `http://localhost:9000` | `http://auth-server:9000` |
| `BOARD_SERVICE_URI` | `http://localhost:9100` | `http://board-service:9100` |
| `CORS_ALLOWED_ORIGIN` | `http://localhost:5173` | `http://localhost:5173` |
| `AUTH_JWKS_URI` | `http://localhost:9000/.well-known/jwks.json` | `http://auth-server:9000/...` |
| `PLATFORM_ISSUER` | `http://localhost:9000` | auth-server 발급 iss와 일치 |
| `PLATFORM_AUDIENCE` | `platform-api` | auth-server 발급 aud와 일치 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | `redis` / `6379` (없으면 rate limit fail-open) |

**Docker DNS 설명:** Docker Compose 네트워크 안에서는 `docker-compose.yml`의 **서비스명이 곧 DNS 호스트명**이 된다. 컨테이너끼리 `auth-server`, `board-service`, `gateway-server` 등으로 직접 찾을 수 있어서, 환경변수에 `http://auth-server:9000` 처럼 서비스명을 사용한다. 로컬 직접 실행 시에는 기본값인 `localhost`가 적용된다. (`CORS_ALLOWED_ORIGIN`은 **브라우저 주소창 기준** 오리진이므로 컨테이너 안에서도 `localhost:5173` 그대로다.)

---

## 요청 로깅 / X-Request-Id

`filter/RequestLoggingFilter.java` — `GlobalFilter` 구현체 (최고 우선순위, `Ordered.HIGHEST_PRECEDENCE`).

동작 순서:
1. 요청 헤더에 `X-Request-Id`가 없거나 비어 있으면 UUID를 생성. 이미 있으면 그 값을 그대로 사용.
2. `headers.set()`으로 다운스트림 요청에 주입 — append가 아니라 **set**이므로 헤더가 항상 정확히 1개다(클라이언트가 보낸 값과 중복되지 않음).
3. `INFO: METHOD /경로 (requestId)` 한 줄 로깅.

같은 `X-Request-Id`가 게이트웨이 로그와 다운스트림 서비스 로그에 함께 남아 요청을 추적할 수 있다. Zipkin/Tempo 백엔드 연동은 아래 확장 포인트 참고.

---

## CORS 중앙화

`SecurityConfig`의 `CorsConfigurationSource` 빈이 **단일 소스**다(yml `globalcors` 아님 — Security의 401 응답에도 CORS 헤더가 붙어야 프론트의 401→refresh 흐름이 살기 때문).

- `allowedOrigins`: `${CORS_ALLOWED_ORIGIN:http://localhost:5173}`
- `allowedMethods`: `GET, POST, PUT, DELETE, OPTIONS`
- `allowedHeaders`: `*` / `allowCredentials`: `true` (RT 쿠키) / `maxAge`: 3600

`allowCredentials: true` 인 이상 `allowedOrigins: "*"` 는 스펙상 불가 — 오리진을 반드시 명시해야 한다. 다중 오리진이 필요해지면 콤마 구분 리스트로 확장한다.

auth-server와 board-service는 더 이상 CORS를 설정하지 않는다. 게이트웨이를 우회한 직접 호출은 내부망 전제이므로 CORS 처리 불필요.

---

## 테스트 / 빌드

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
.\gradlew.bat test     # JUnit 전체 — 다운스트림 서비스·Redis 없이 실행 가능
```

| 테스트 | 검증 내용 |
|---|---|
| `RouteConfigTest` | 6개 라우트 id 등록 + 인증 라우트에 RequestRateLimiter 필터 존재 |
| `CorsTest` | `:5173` 오리진의 프리플라이트(OPTIONS)가 200 + `Access-Control-Allow-Origin` 응답 |
| `SecurityConfigTest` | 보호 경로 무토큰/위조 토큰 401(+401에도 CORS 헤더), 공개 경로는 보안 통과 |
| `BoardFallbackTest` | board 다운 시 fallback 503 JSON |
| `HttpClientTimeoutTest` | 전역 connect/response 타임아웃 바인딩 |
| `IpKeyResolverTest` | rate limit 키 = 클라이언트 IP (없으면 "unknown") |
| `RequestLoggingFilterTest` | `X-Request-Id` 생성/보존/형식 검증 후 재발급 + 헤더 1개 유지 |

테스트는 실제 다운스트림으로 프록시하지 않는다 — 라우트 정의·CORS·필터만 검증하므로 auth-server/board-service 기동 불필요.

---

## 확장 포인트 (미구현 — 위치만 표시)

| 기능 | 구현 방향 |
|---|---|
| **서비스 디스커버리** | Eureka 도입 시 정적 URI를 `lb://service-name` 으로 교체 |
| **분산 추적 백엔드** | Micrometer Tracing + Zipkin/Tempo exporter (수동 X-Request-Id 대체) |
| **요청 크기 제한 / 보안 응답 헤더** | `RequestSize` 필터, SecureHeaders |

> Rate Limiting·서킷브레이커·JWT 조기차단은 2026-07-03 구현 완료(위 역할 섹션 참고).

---

## 디렉토리

```
src/main/java/com/platform/gateway/
├─ GatewayApplication.java
├─ config/
│  ├─ RateLimitConfig.java          ipKeyResolver 빈 (rate limit 키 = 클라이언트 IP)
│  └─ SecurityConfig.java           JWT 조기차단 보안체인 + JWKS 디코더 + CORS 단일 소스
├─ security/AudienceValidator.java  aud 클레임 검증 (board-service와 동일 패턴)
├─ web/FallbackController.java      /fallback/board → 503
└─ filter/RequestLoggingFilter.java GlobalFilter: X-Request-Id 검증/재발급 + 요청 1줄 로깅
src/main/resources/application.yml  라우트(+rate limit·CB 필터) + 타임아웃 + platform.* 계약
```

설계 문서: [`docs/superpowers/specs/2026-06-30-msa-gateway-design.md`](../docs/superpowers/specs/2026-06-30-msa-gateway-design.md)

## 트러블슈팅

- **`Gradle requires JVM 17 or later`** → `JAVA_HOME`을 JDK 24로. (기본이 11)
- **라우트가 전혀 안 먹고 전부 404** → 설정 prefix 확인. Boot 4 / Spring Cloud 2025.x는 `spring.cloud.gateway.server.webflux.routes` 다(구버전 `spring.cloud.gateway.routes` 아님).
- **프록시 응답이 `500/Connection refused`** → 해당 다운스트림(auth :9000 / board :9100)이 안 떠 있음. 게이트웨이 로그의 `X-Request-Id` 라인으로 어느 경로였는지 확인.
- **브라우저 콘솔에 CORS 에러** → 프론트 오리진이 `CORS_ALLOWED_ORIGIN`과 정확히 일치하는지(스킴·호스트·포트) 확인. 쿠키가 필요한 요청은 프론트에서 `credentials: 'include'`도 필요.
- **다운스트림에서 CORS 헤더가 중복** → auth-server/board-service에 CORS 설정이 남아 있는 것. 다운스트림 CORS는 전부 제거해야 한다(게이트웨이 단일 책임).
- **`:8000` 기동 실패(Address already in use)** → 이전 gateway 프로세스가 살아 있음. `netstat -ano | findstr :8000` 후 해당 PID 종료.
