# gateway-server

모든 클라이언트 트래픽의 **단일 진입점(Single Entry Point)**.
라우팅 + CORS 중앙화 + 요청 로깅(traceId) + **rate limiting + 타임아웃/서킷브레이커 + JWT 조기차단 + X-Forwarded 프록시 처리**를 담당한다.

> **이중 검증 원칙**: 게이트웨이의 JWT 검증은 **1차 방어**(쓰레기 트래픽 조기 401)일 뿐, 각 다운스트림 서비스의 자체 JWKS 검증(**최종 방어**)을 대체하지 않는다 — zero-trust.

> 별도 git repo: `github.com/chanho4702/gateway-server` (브랜치 `main`). 우산 repo(MSA_TEMPLATE)에서는 gitignore 됨.

---

## 역할 / 아키텍처

```
  myFront(:5173) ──▶ gateway-server(:8000) ──┬──▶ lb://auth-server    JWT발급·JWKS·/api/me
   (base URL 1개)    Spring Cloud Gateway     │
                     라우팅 / CORS / 로깅      └──▶ lb://board-service  /api/board/**
                     rate limit / CB / JWT 1차검증        ▲
                     X-Forwarded 신뢰(trusted-proxies)
                     서비스 디스커버리(lb://) ──── eureka-server(:8761) 레지스트리
                     infra: Keycloak(:8080) · Postgres(:5433) · Redis(:6379)
```

- **CORS 중앙화** — 브라우저(myFront:5173)의 모든 요청이 게이트웨이를 통과하므로 CORS는 여기 한 곳에서만 처리한다. auth-server와 board-service는 CORS를 설정하지 않는다.
- **JWT 조기차단(1차 방어)** — 보호 경로(`POST /api/board/**`, `/api/me` 등)의 무토큰/위조/만료 토큰을 다운스트림에 닿기 전에 401로 차단. JWKS + issuer + audience 검증(board-service와 동일 계약). 공개 경로(`GET /api/board/posts/**`, OIDC 흐름, `/api/auth/**`, JWKS, `/fallback/**`)는 통과. **경로 정책은 board-service SecurityConfig와 동기 유지할 것.** 유효 토큰의 `Authorization` 헤더는 그대로 다운스트림에 전달된다.
- **X-Forwarded 프록시 처리(trusted-proxies)** — nginx 통합배포처럼 게이트웨이 앞에 리버스 프록시를 두면, SCG 4.1+ 보안 기본값은 신뢰하지 않는 `X-Forwarded-*` 헤더를 제거한다. 그러면 nginx가 붙인 `X-Forwarded-Host`(localhost)가 auth-server에 도달하지 못해 OIDC `redirect_uri`가 eureka IP(:9000)로 구성되고 Keycloak이 거부한다. `trusted-proxies` 정규식으로 루프백 + 사설대역(도커 NAT 포함)을 신뢰하도록 열어 이 문제를 해결한다(아래 전용 섹션 참고).
- **Rate limiting** — 인증 경로 IP 기준(`/api/auth/**` 5 req/s burst 10, `/oauth2/**`·`/login/**` 10/20). Redis 백엔드, **Redis 부재 시 fail-open**(요청 통과)이라 dev에서 Redis 없이도 동작 — 단 이때 해당 경로 요청마다 `Error calling rate limiter lua` ERROR 로그가 남는 것은 정상. IP 키는 게이트웨이가 클라이언트에 직접 노출되는 전제 — LB/프록시 뒤에 두면 `XForwardedRemoteAddressResolver` 기반 KeyResolver로 교체할 것.
- **회복탄력성** — 전역 connect 3s/response 10s 타임아웃. board 라우트에 CircuitBreaker(+멱등 GET 한정 Retry 2회, 연결 실패=`IOException`만) → 불능 시 `/fallback/board` 503 `{"error":"board_unavailable"}`. Resilience4j TimeLimiter는 board 인스턴스에 11s로 명시(미설정 시 기본 1s가 정상 응답까지 잘라버림).
- **요청 로깅 / traceId** — `GlobalFilter`가 매 요청의 `X-Request-Id`를 보장(없거나 형식 불량이면 UUID 재발급)하고 다운스트림으로 전파한 뒤 `METHOD 경로 (requestId)` 한 줄을 로깅한다.
- **경로 불변(No StripPrefix)** — 클라이언트가 보낸 경로 = 서비스가 받는 경로. 서비스 입장에서 게이트웨이 유무에 따라 경로가 달라지지 않는다.
- **서비스 디스커버리(lb://)** — 라우트 uri 기본값이 `lb://auth-server`/`lb://board-service`. 유레카 레지스트리(:8761)에서 인스턴스를 찾아 클라이언트 사이드 로드밸런싱(라운드로빈)한다. 다운스트림 주소·포트·대수가 바뀌어도 게이트웨이 설정은 불변. 유레카 서버가 잠깐 죽어도 로컬 캐시(30s 갱신)로 라우팅은 유지된다. `AUTH_SERVER_URI`/`BOARD_SERVICE_URI` env를 주입하면 유레카 없이 직접 URI로도 동작(테스트·탈출구).

**요청 처리 순서:** Security(CORS 프리플라이트 응답·JWT 검증·401 조기차단) → `RequestLoggingFilter`(`HIGHEST_PRECEDENCE` GlobalFilter — 401로 잘린 요청은 여기 안 옴) → 라우트 매칭(rate limit·CB 필터) → 다운스트림 프록시.

## 기술 스택

Spring Cloud Gateway **WebFlux** · Spring Boot **4.0.6** · Java **24** · Spring Cloud BOM **2025.1.2** · Gradle

의존성(build.gradle 실측):

| 의존성 | 용도 |
|---|---|
| `spring-cloud-starter-gateway-server-webflux` | 게이트웨이 본체(라우팅·필터) |
| `spring-cloud-starter-netflix-eureka-client` | 서비스 디스커버리(lb:// 해석·자기등록, LoadBalancer 전이 포함) |
| `spring-boot-starter-data-redis-reactive` | RequestRateLimiter 토큰 버킷 저장소 |
| `spring-cloud-starter-circuitbreaker-reactor-resilience4j` | CircuitBreaker·TimeLimiter 필터 |
| `spring-boot-starter-security` + `oauth2-resource-server` | JWT 조기차단(1차 방어) |
| `spring-boot-starter-webflux` (test only) | WebTestClient |

> Boot 4 / Spring Cloud 2025.x 기준 설정 prefix는 `spring.cloud.gateway.server.webflux.*` 다. 구버전 문서의 `spring.cloud.gateway.routes` 예시를 그대로 붙여넣으면 라우트가 조용히 무시되니 주의.

---

## 빠른 시작

**전제:** eureka-server(:8761) · auth-server(:9000) · board-service(:9100) · infra(Keycloak+Postgres) 가 먼저 떠 있어야 한다. (게이트웨이 자체는 아무것도 없이도 뜨지만, 프록시 요청은 인스턴스 미발견으로 503이 난다. 기동 순서는 강제 아님 — 서비스가 유레카에 등록되는 대로 라우팅이 살아난다.)

### gradlew (터미널)

```powershell
# Windows PowerShell — JDK 24 필요(기본 JDK가 11이면 실패)
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
.\gradlew.bat bootRun        # :8000 기동
```

### IntelliJ (.run 공유 Config)

repo에 커밋된 `.run/bootRun.run.xml`(Gradle `bootRun` 태스크)이 IntelliJ 실행 버튼에 자동으로 나타난다. IDE에서 재시작·디버깅한다. 백엔드 4서비스 공통 개발 방식(프론트는 VSCode + `scripts/dev-up.ps1`, 우산 repo README 참고).

기동 확인: `http://localhost:8000/.well-known/jwks.json` → auth-server JWKS 그대로 반환.

> 포트 맵: gateway 8000 / eureka 8761 / auth 9000 / board 9100 / Keycloak 8080 / Postgres 5433 / Redis 6379 / myFront 5173.

### Docker (컨테이너)

`Dockerfile`은 **런타임 전용**이다 — jar를 빌드하지 않고 이미 만들어진 `build/libs/app.jar`를 복사한다. 먼저 `gradlew bootJar`로 jar를 만든 뒤 이미지를 빌드한다.

```powershell
.\gradlew.bat bootJar                    # build/libs/app.jar 생성
docker build -t gateway-server .          # eclipse-temurin:24-jre 베이스, EXPOSE 8000
```

Compose 네트워크에서는 서비스명이 곧 DNS 호스트명이므로 `EUREKA_URI`/`AUTH_SERVER_URI` 등을 서비스명 기준으로 주입한다(아래 환경 변수 표).

---

## 라우팅 규칙

`application.yml`에 선언. **No StripPrefix — 클라이언트가 보낸 경로 = 서비스가 받는 경로.**
대상은 유레카 서비스 이름 기준 `lb://` (env로 직접 URI 오버라이드 가능).

| 라우트 id | 경로 패턴 | → 대상 | 필터 / 비고 |
|---|---|---|---|
| `board` | `/api/board/**` | `lb://board-service` | CircuitBreaker(→`/fallback/board`) + GET 한정 Retry 2회 |
| `auth-oauth2` | `/oauth2/**` | `lb://auth-server` | OIDC 로그인 시작·콜백. RateLimiter 10/20 |
| `auth-login` | `/login/**` | `lb://auth-server` | OIDC 리다이렉트 흐름. RateLimiter 10/20 |
| `auth-api` | `/api/auth/**` | `lb://auth-server` | refresh · logout. RateLimiter 5/10 |
| `auth-jwks` | `/.well-known/**` | `lb://auth-server` | JWKS 공개키. 필터 없음 |
| `auth-me` | `/api/me` | `lb://auth-server` | 자체 JWT 기반 사용자 정보. RateLimiter 없음(의도 — Security가 무토큰/위조를 이미 401 차단, 브루트포스 표면 아님) |

라우트 id·lb:// 기본값은 `RouteConfigTest`가 검증하므로, 바꾸면 테스트도 함께 갱신한다.
`lb://` 뒤의 이름은 대상 서비스의 `spring.application.name`과 일치해야 한다(유레카 등록 ID).

---

## 새 서비스 라우트 추가 (확장 포인트)

1. 대상 서비스에 `eureka-client` 의존성 추가 + `spring.application.name` 확인(= `lb://` 뒤 이름).
2. 게이트웨이 `application.yml`의 `spring.cloud.gateway.server.webflux.routes`에 라우트 블록 추가:

```yaml
- id: my-service
  uri: ${MY_SERVICE_URI:lb://my-service}   # env로 유레카 우회 가능
  predicates:
    - Path=/api/my/**
  # (선택) 브루트포스 표면이면 RequestRateLimiter, 다운스트림 불안정하면 CircuitBreaker 필터 추가
```

3. 보호가 필요한 경로면 `SecurityConfig.securityWebFilterChain`의 `authorizeExchange` 정책을 board-service SecurityConfig와 동기해 조정(공개 경로는 `permitAll`, 나머지는 `authenticated`).
4. 게이트웨이 재기동. **다운스트림 주소·포트가 바뀌어도 유레카가 해석하므로 이후 설정 변경 불필요.**

---

## 환경 변수

| 변수 | 기본값 (로컬 실행) | Docker Compose 예시 |
|---|---|---|
| `EUREKA_URI` | `http://localhost:8761/eureka` | `http://eureka-server:8761/eureka` |
| `AUTH_SERVER_URI` | `lb://auth-server` (유레카 해석) | 직접 URI로 유레카 우회 가능 |
| `BOARD_SERVICE_URI` | `lb://board-service` (유레카 해석) | 직접 URI로 유레카 우회 가능 |
| `CORS_ALLOWED_ORIGIN` | `http://localhost:5173` | `http://localhost:5173` |
| `AUTH_JWKS_URI` | `http://localhost:9000/.well-known/jwks.json` | `http://auth-server:9000/...` |
| `PLATFORM_ISSUER` | `http://localhost:9000` | auth-server 발급 iss와 일치 |
| `PLATFORM_AUDIENCE` | `platform-api` | auth-server 발급 aud와 일치 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | `redis` / `6379` (없으면 rate limit fail-open) |

**Docker DNS 설명:** Docker Compose 네트워크 안에서는 `docker-compose.yml`의 **서비스명이 곧 DNS 호스트명**이 된다. 컨테이너끼리 `auth-server`, `board-service`, `gateway-server` 등으로 직접 찾을 수 있어서, 환경변수에 `http://auth-server:9000` 처럼 서비스명을 사용한다. 로컬 직접 실행 시에는 기본값인 `localhost`가 적용된다. (`CORS_ALLOWED_ORIGIN`은 **브라우저 주소창 기준** 오리진이므로 컨테이너 안에서도 `localhost:5173` 그대로다.)

> `trusted-proxies`는 env가 아니라 `application.yml`에 정규식으로 하드코딩돼 있다(dev 전용). nginx/프록시 토폴로지가 바뀌면 yml을 직접 고친다 — 아래 섹션 참고.

---

## X-Forwarded 프록시 처리 (trusted-proxies)

`application.yml`의 `spring.cloud.gateway.server.webflux.trusted-proxies` (정규식).

SCG 4.1+는 보안상 **신뢰하지 않는 소스의 `X-Forwarded-*` 헤더를 제거**하는 것이 기본값이다. nginx 통합배포(단일 오리진 `http://localhost` 뒤에 프론트 3앱 + 게이트웨이)에서는 nginx가 `X-Forwarded-Host: localhost`를 붙여 게이트웨이로 넘기는데, 이 헤더가 신뢰되지 않고 제거되면:

- 게이트웨이는 프록시가 없는 것으로 판단 → 자기 자신의 실주소(유레카 등록 IP, `:9000` 등)를 기준으로 `X-Forwarded-Host`를 재구성.
- 그 값이 auth-server에 전달되어 OIDC `redirect_uri`가 브라우저가 아는 `http://localhost`가 아니라 내부 IP로 생성됨.
- **Keycloak이 등록되지 않은 redirect_uri라며 로그인을 거부.**

이를 막기 위해 루프백 + 사설대역(도커 NAT 포함)을 신뢰하도록 정규식을 열어 둔다:

```
trusted-proxies: "127\.0\.0\.1|::1|0:0:0:0:0:0:0:1|10\..*|172\.(1[6-9]|2[0-9]|3[01])\..*|192\.168\..*"
```

> **dev 전용.** 운영에서는 실제 프록시(nginx) IP만 좁게 신뢰해야 한다. 이 사설대역 전체 허용은 로컬/도커 개발 편의를 위한 것이다. (이 문제는 SCG `trusted-proxies` 미설정과 stale Keycloak realm 2중 원인으로 나타났던 redirect_uri 거부의 게이트웨이 측 원인.)

---

## 요청 로깅 / X-Request-Id

`filter/RequestLoggingFilter.java` — `GlobalFilter` 구현체 (최고 우선순위, `Ordered.HIGHEST_PRECEDENCE`).

동작 순서:
1. 요청 헤더에 `X-Request-Id`가 없거나 **형식이 안전하지 않으면**(허용: `[A-Za-z0-9._-]{1,64}`, 로그 인젝션 방지) UUID를 생성. 안전한 값이면 그대로 사용.
2. `headers.set()`으로 다운스트림 요청에 주입 — append가 아니라 **set**이므로 헤더가 항상 정확히 1개다(클라이언트가 보낸 값과 중복되지 않음).
3. `INFO: METHOD /경로 (requestId)` 한 줄 로깅.

같은 `X-Request-Id`가 게이트웨이 로그와 다운스트림 서비스 로그에 함께 남아 요청을 추적할 수 있다. Zipkin/Tempo 백엔드 연동은 아래 확장 포인트 참고.

---

## CORS 중앙화

`SecurityConfig`의 `CorsConfigurationSource` 빈이 **단일 소스**다(yml `globalcors` 아님 — Security의 401 응답에도 CORS 헤더가 붙어야 프론트의 401→refresh 흐름이 살기 때문).

- `allowedOrigins`: `${platform.cors-allowed-origin}` = `${CORS_ALLOWED_ORIGIN:http://localhost:5173}`
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
| `RouteConfigTest` | 6개 라우트 id 등록 + 기본 uri가 lb://(서비스 디스커버리) + 인증 라우트에 RequestRateLimiter 필터 존재 |
| `CorsTest` | `:5173` 오리진의 프리플라이트(OPTIONS)가 200 + `Access-Control-Allow-Origin` 응답 |
| `SecurityConfigTest` | 보호 경로 무토큰/위조 토큰 401(+401에도 CORS 헤더), 공개 경로는 보안 통과 |
| `AudienceValidatorTest` | aud 클레임 일치/불일치 검증 |
| `BoardFallbackTest` | board 다운 시 fallback 503 JSON |
| `SlowBoardDownstreamTest` | 느린 다운스트림에서 타임아웃/CB 동작 |
| `HttpClientTimeoutTest` | 전역 connect/response 타임아웃 바인딩 |
| `IpKeyResolverTest` | rate limit 키 = 클라이언트 IP (없으면 "unknown") |
| `RequestLoggingFilterTest` | `X-Request-Id` 생성/보존/형식 검증 후 재발급 + 헤더 1개 유지 |

테스트는 실제 다운스트림으로 프록시하지 않는다 — 라우트 정의·CORS·필터만 검증하므로 auth-server/board-service 기동 불필요.

빌드 산출물: `bootJar` → `build/libs/app.jar` (archiveFileName 고정, Dockerfile이 이 이름으로 복사).

---

## 확장 포인트 (미구현 — 위치만 표시)

| 기능 | 구현 방향 |
|---|---|
| **분산 추적 백엔드** | Micrometer Tracing + Zipkin/Tempo exporter (수동 X-Request-Id 대체) |
| **요청 크기 제한 / 보안 응답 헤더** | `RequestSize` 필터, SecureHeaders |
| **프록시 뒤 rate limit 키** | `XForwardedRemoteAddressResolver` 기반 KeyResolver (게이트웨이가 프록시 뒤로 갈 때) |

> Rate Limiting·서킷브레이커·JWT 조기차단은 2026-07-03, 서비스 디스커버리(Eureka lb://)는 2026-07-05 구현 완료(위 역할 섹션 참고).

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
src/main/resources/application.yml  라우트(+rate limit·CB 필터) + trusted-proxies + 타임아웃 + platform.* 계약
.run/bootRun.run.xml                IntelliJ 공유 Run Config (Gradle bootRun)
Dockerfile                          런타임 전용 (temurin:24-jre, build/libs/app.jar 복사)
```

설계 문서: [`docs/superpowers/specs/2026-06-30-msa-gateway-design.md`](../docs/superpowers/specs/2026-06-30-msa-gateway-design.md)

## 트러블슈팅

- **`Gradle requires JVM 17 or later`** → `JAVA_HOME`을 JDK 24로. (기본이 11)
- **라우트가 전혀 안 먹고 전부 404** → 설정 prefix 확인. Boot 4 / Spring Cloud 2025.x는 `spring.cloud.gateway.server.webflux.routes` 다(구버전 `spring.cloud.gateway.routes` 아님).
- **nginx 통합배포에서 로그인 시 Keycloak이 `redirect_uri` 거부** → 게이트웨이 `trusted-proxies`에 프록시/도커 대역이 포함됐는지 확인(위 X-Forwarded 섹션). Keycloak realm의 Valid Redirect URIs가 stale하지 않은지도 함께 점검(2중 원인).
- **프록시 응답이 `503 Service Unavailable`** → 해당 다운스트림이 유레카에 등록 안 됨(미기동 또는 등록 전파 대기 최대 30s). `http://localhost:8761` 대시보드에서 등록 상태 확인.
- **프록시 응답이 `500 UnknownHostException ... mshome.net`** → 다운스트림이 유레카에 DNS 해석 불가 호스트명으로 등록된 것. 각 서비스 `eureka.instance.prefer-ip-address: true` 확인 (Windows/Hyper-V에서 필수 — E2E 실측).
- **다운스트림이 `Port 910x was already in use`로 기동 실패 (리스너 없음)** → Docker/Hyper-V가 부팅 시 예약한 포트 제외 범위에 걸린 것. `netsh interface ipv4 show excludedportrange protocol=tcp`로 확인 후 범위 밖 포트로 기동(`$env:SERVER_PORT`). 유레카 덕에 포트를 바꿔도 게이트웨이 설정은 불변.
- **브라우저 콘솔에 CORS 에러** → 프론트 오리진이 `CORS_ALLOWED_ORIGIN`과 정확히 일치하는지(스킴·호스트·포트) 확인. 쿠키가 필요한 요청은 프론트에서 `credentials: 'include'`도 필요.
- **다운스트림에서 CORS 헤더가 중복** → auth-server/board-service에 CORS 설정이 남아 있는 것. 다운스트림 CORS는 전부 제거해야 한다(게이트웨이 단일 책임).
- **`:8000` 기동 실패(Address already in use)** → 이전 gateway 프로세스가 살아 있음. `netstat -ano | findstr :8000` 후 해당 PID 종료.
