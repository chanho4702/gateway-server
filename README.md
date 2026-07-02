# gateway-server

모든 클라이언트 트래픽의 **단일 진입점(Single Entry Point)**.
순수 라우팅 + CORS 중앙화 + 요청 로깅(traceId) 만 담당한다. **Spring Security 없음 — 토큰 검증은 각 다운스트림 서비스가 자체 수행한다.**

> 별도 git repo: `github.com/chanho4702/gateway-server` (브랜치 `main`). 우산 repo(MSA_TEMPLATE)에서는 gitignore 됨.

---

## 역할 / 아키텍처

```
  myFront(:5173) ──▶ gateway-server(:8000) ──┬──▶ auth-server(:9000)   JWT발급·JWKS·/api/me
   (base URL 1개)    Spring Cloud Gateway     │
                     라우팅 / CORS / 로깅      └──▶ board-service(:9100) /api/board/**
                     토큰검증 X

                     infra: Keycloak(:8080) · Postgres(:5433)
```

- **CORS 중앙화** — 브라우저(myFront:5173)의 모든 요청이 게이트웨이를 통과하므로 CORS는 여기 한 곳에서만 처리한다. auth-server와 board-service는 CORS를 설정하지 않는다.
- **요청 로깅 / traceId** — `GlobalFilter`가 매 요청의 `X-Request-Id`를 보장(없으면 UUID 생성)하고 다운스트림으로 전파한 뒤 `METHOD 경로 (requestId)` 한 줄을 로깅한다.
- **토큰 검증 없음** — 각 서비스가 auth-server `/.well-known/jwks.json`의 RS256 공개키로 자체 검증한다(분산 검증 패턴). 게이트웨이는 `Authorization` 헤더·쿠키를 손대지 않고 그대로 통과시킨다.
- **경로 불변(No StripPrefix)** — 클라이언트가 보낸 경로 = 서비스가 받는 경로. 서비스 입장에서 게이트웨이 유무에 따라 경로가 달라지지 않는다.

**요청 처리 순서:** CORS(프리플라이트는 여기서 응답 종료) → `RequestLoggingFilter`(`HIGHEST_PRECEDENCE`) → 라우트 매칭 → 다운스트림 프록시.

## 기술 스택

Spring Cloud Gateway **WebFlux** · Spring Boot 4.0.6 · Java 24 · Spring Cloud BOM **2025.1.2** · Gradle

의존성: `spring-cloud-starter-gateway-server-webflux` 단일 (Spring Security 없음). 테스트에만 `spring-boot-starter-webflux`(WebTestClient) 추가.

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

`application.yml` `globalcors` 설정으로 **모든 라우트**(`[/**]`)에 동일한 CORS 정책을 적용한다.

- `allowedOrigins`: `${CORS_ALLOWED_ORIGIN:http://localhost:5173}`
- `allowedMethods`: `GET, POST, PUT, DELETE, OPTIONS`
- `allowedHeaders`: `*`
- `allowCredentials`: `true` (RT 쿠키 전송 지원)

`allowCredentials: true` 인 이상 `allowedOrigins: "*"` 는 스펙상 불가 — 오리진을 반드시 명시해야 한다. 다중 오리진이 필요해지면 콤마 구분 리스트로 확장한다.

auth-server와 board-service는 더 이상 CORS를 설정하지 않는다. 게이트웨이를 우회한 직접 호출은 내부망 전제이므로 CORS 처리 불필요.

---

## 테스트 / 빌드

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
.\gradlew.bat test     # JUnit 전체 (현재 4개) — 다운스트림 서비스 없이 실행 가능
```

| 테스트 | 검증 내용 |
|---|---|
| `RouteConfigTest` | 6개 라우트 id가 모두 등록되어 있는지 (`RouteLocator` 기반) |
| `CorsTest` | `:5173` 오리진의 프리플라이트(OPTIONS)가 200 + `Access-Control-Allow-Origin` 응답 (RANDOM_PORT 실서버) |
| `RequestLoggingFilterTest` | `X-Request-Id` 없으면 생성 / 있으면 보존 + **헤더 1개 유지** (순수 단위 테스트) |

테스트는 실제 다운스트림으로 프록시하지 않는다 — 라우트 정의·CORS·필터만 검증하므로 auth-server/board-service 기동 불필요.

---

## 확장 포인트 (미구현 — 위치만 표시)

| 기능 | 구현 방향 |
|---|---|
| **Rate Limiting** | `RequestRateLimiter` 필터 + Redis. 라우트 `filters:` 에 추가 |
| **서킷 브레이커** | Resilience4j `CircuitBreaker` 필터 + fallback 라우트 |
| **서비스 디스커버리** | Eureka 도입 시 정적 URI를 `lb://service-name` 으로 교체 |
| **분산 추적 백엔드** | Micrometer Tracing + Zipkin/Tempo exporter |

> YAGNI 원칙에 따라 현재는 구현 없이 코드 주석으로만 위치를 표시한다.

---

## 디렉토리

```
src/main/java/com/platform/gateway/
├─ GatewayApplication.java
└─ filter/
   └─ RequestLoggingFilter.java    GlobalFilter: X-Request-Id 생성/전파 + 요청 1줄 로깅
src/main/resources/application.yml  라우트 정의(정적) + CORS globalcors + 환경변수 외부화
src/test/java/com/platform/gateway/
├─ RouteConfigTest.java             라우트 id 존재 검증
├─ CorsTest.java                    프리플라이트 CORS 검증
└─ filter/RequestLoggingFilterTest.java
```

설계 문서: [`docs/superpowers/specs/2026-06-30-msa-gateway-design.md`](../docs/superpowers/specs/2026-06-30-msa-gateway-design.md)

## 트러블슈팅

- **`Gradle requires JVM 17 or later`** → `JAVA_HOME`을 JDK 24로. (기본이 11)
- **라우트가 전혀 안 먹고 전부 404** → 설정 prefix 확인. Boot 4 / Spring Cloud 2025.x는 `spring.cloud.gateway.server.webflux.routes` 다(구버전 `spring.cloud.gateway.routes` 아님).
- **프록시 응답이 `500/Connection refused`** → 해당 다운스트림(auth :9000 / board :9100)이 안 떠 있음. 게이트웨이 로그의 `X-Request-Id` 라인으로 어느 경로였는지 확인.
- **브라우저 콘솔에 CORS 에러** → 프론트 오리진이 `CORS_ALLOWED_ORIGIN`과 정확히 일치하는지(스킴·호스트·포트) 확인. 쿠키가 필요한 요청은 프론트에서 `credentials: 'include'`도 필요.
- **다운스트림에서 CORS 헤더가 중복** → auth-server/board-service에 CORS 설정이 남아 있는 것. 다운스트림 CORS는 전부 제거해야 한다(게이트웨이 단일 책임).
- **`:8000` 기동 실패(Address already in use)** → 이전 gateway 프로세스가 살아 있음. `netstat -ano | findstr :8000` 후 해당 PID 종료.
