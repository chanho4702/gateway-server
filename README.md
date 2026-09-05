# gateway-server

[![CI](https://github.com/chanho4702/gateway-server/actions/workflows/ci.yml/badge.svg)](https://github.com/chanho4702/gateway-server/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-24-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot&logoColor=white)
![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2025.1.2-6DB33F)
![Gateway](https://img.shields.io/badge/Spring%20Cloud-Gateway-6DB33F)

모든 클라이언트 트래픽의 **단일 진입점(Single Entry Point)**.
라우팅 + CORS 중앙화 + 요청 로깅(traceId) + **rate limiting + 타임아웃/서킷브레이커 + JWT 조기차단 + X-Forwarded 프록시 처리**를 담당한다.

> **이중 검증 원칙**: 게이트웨이의 JWT 검증은 **1차 방어**(쓰레기 트래픽 조기 401)일 뿐, 각 다운스트림 서비스의 자체 JWKS 검증(**최종 방어**)을 대체하지 않는다 — zero-trust.

> 별도 git repo: [chanho4702/gateway-server](https://github.com/chanho4702/gateway-server). 전체 구성은 [infra-settings](https://github.com/chanho4702/infra-settings) 참고.

---

## 역할 / 아키텍처

```
  브라우저 ─▶ nginx(:80) ─▶ gateway-server(:8000) ─┬─▶ auth-server(:9000)     JWT발급·JWKS·/api/me
   3-SPA 단일 오리진        Spring Cloud Gateway    ├─▶ board-service(:9100)   /api/board/**
                           라우팅 / CORS / 로깅     ├─▶ org-service(:9130)     /api/org/**
                           rate limit / CB          ├─▶ wiki-backend(:9110)    /api/wiki/**
                           JWT 1차검증(Security)     ├─▶ alm-backend(:9120)     /api/alm/**
                                                   ├─▶ search-service(:9140)  /api/search/**
                                                   └─▶ collaboration(:9150)   /api/wiki/collaboration
                           X-Forwarded 신뢰(trusted-proxies)
                           lb:// 디스커버리 ── eureka-server(:8761)  auth·board
                           *_SERVICE_URI 로 DNS 직결 ── org·wiki·alm·search
                           infra: Keycloak(:8080) · Postgres(:5433) · Redis(:6379)
```

- **CORS 중앙화** — 브라우저(myFront:5173)의 모든 요청이 게이트웨이를 통과하므로 CORS는 여기 한 곳에서만 처리한다. auth-server와 board-service는 CORS를 설정하지 않는다.
- **JWT 조기차단(1차 방어)** — 정책은 `anyExchange().authenticated()`이고 공개 경로만 열어 둔다. 즉 **명시적으로 열지 않은 경로는 전부 인증 필수**다. 무토큰/위조/만료 토큰은 다운스트림에 닿기 전에 401로 차단된다. JWKS + issuer + audience 검증(board-service와 동일 계약). 유효 토큰의 `Authorization` 헤더는 그대로 다운스트림에 전달된다.
  - 공개(permitAll): OPTIONS 프리플라이트 · `/oauth2/**` · `/login/**` · `/api/auth/**` · `/.well-known/**` · `/fallback/**` · `GET /api/board/posts/**`
  - 인증 필수: `/api/me` · `/api/org/**` · `/api/wiki/**` · `/api/alm/**` · **`/api/search/**`**(GraphQL·재색인 모두) · 그 외 전부
  - **경로 정책은 board-service SecurityConfig와 동기 유지할 것.** `/fallback/**`을 열어 두지 않으면 서킷브레이커가 forward한 내부 요청이 401로 죽는다.
- **개인 API 토큰(PAT) 교환 — 보안 체인 앞** — 외부 클라이언트(스크립트·CI)가 `Authorization: Bearer chanho_pat_…` 로 기존 API를 부르면, `PatExchangeWebFilter`가 auth-server에서 플랫폼 JWT를 받아 헤더를 갈아끼운다. 다운스트림 서비스는 한 줄도 바뀌지 않는다 — 지금처럼 JWT만 본다(아래 전용 섹션).
- **X-Forwarded 프록시 처리(trusted-proxies)** — nginx 통합배포처럼 게이트웨이 앞에 리버스 프록시를 두면, SCG 4.1+ 보안 기본값은 신뢰하지 않는 `X-Forwarded-*` 헤더를 제거한다. 그러면 nginx가 붙인 `X-Forwarded-Host`(localhost)가 auth-server에 도달하지 못해 OIDC `redirect_uri`가 eureka IP(:9000)로 구성되고 Keycloak이 거부한다. `trusted-proxies` 정규식으로 루프백 + 사설대역(도커 NAT 포함)을 신뢰하도록 열어 이 문제를 해결한다(아래 전용 섹션 참고).
- **Rate limiting** — IP 기준. 인증 경로(`/api/auth/**` 5 req/s burst 10, `/oauth2/**`·`/login/**` 10/20)와 **검색 경로(`/api/search/**` 5/15)**. Redis 백엔드, **Redis 부재 시 fail-open**(요청 통과)이라 dev에서 Redis 없이도 동작 — 단 이때 해당 경로 요청마다 `Error calling rate limiter lua` ERROR 로그가 남는 것은 정상. **IP 키는 nginx 1홉 뒤의 실 클라이언트 IP**다 — `RateLimitConfig`가 `XForwardedRemoteAddressResolver.maxTrustedIndex(1)`로 `X-Forwarded-For`의 맨 오른쪽(nginx가 붙인 값)만 채택한다. `getRemoteAddress()`를 그대로 쓰면 nginx/도커 NAT IP 하나로 고정돼 전 클라이언트가 단일 버킷을 공유한다(정상 사용자 상호 차단 + 공격자 격리 불가). 신뢰 홉이 1개라 클라이언트가 XFF를 위조해도 무력하다.
- **회복탄력성** — 전역 connect 3s/response 10s 타임아웃. board 라우트에 CircuitBreaker(+멱등 GET 한정 Retry 2회, 연결 실패=`IOException`만) → 불능 시 `/fallback/board` 503 `{"error":"board_unavailable"}`. Resilience4j TimeLimiter는 board 인스턴스에 11s로 명시(미설정 시 기본 1s가 정상 응답까지 잘라버림).
- **요청 로깅 / traceId** — `GlobalFilter`가 매 요청의 `X-Request-Id`를 보장(없거나 형식 불량이면 UUID 재발급)하고 다운스트림으로 전파한 뒤 `METHOD 경로 (requestId)` 한 줄을 로깅한다.
- **경로 불변(No StripPrefix) — 단 `search` 라우트는 예외** — 원칙은 "클라이언트가 보낸 경로 = 서비스가 받는 경로"다. 서비스 입장에서 게이트웨이 유무에 따라 경로가 달라지지 않는다. **search-service만 `StripPrefix=2`로 접두사를 뗀다** — GraphQL은 단일 URL(`/graphql`)이라 서비스 쪽에 `/api/search` 접두사를 붙일 자리가 없고, 관리 REST도 그에 맞춰 `/admin/reindex`로 통일했기 때문이다(아래 라우팅 표).
- **서비스 디스커버리 — 라우트마다 다르다(하이브리드)** — 라우트 uri 기본값은 전부 `lb://<서비스명>`이고, 유레카 레지스트리(:8761)에서 인스턴스를 찾아 클라이언트 사이드 로드밸런싱(라운드로빈)한다. 유레카 서버가 잠깐 죽어도 로컬 캐시(30s 갱신)로 라우팅은 유지된다.
  **컨테이너 배포판도 유레카를 쓴다 — 다만 전부는 아니다.** `auth-server`·`board-service`는 컨테이너에서도 유레카에 등록하므로 `lb://`로 찾고(게이트웨이에 `EUREKA_URI`가 주입돼 있다), `org-service`·`search-service`는 `docker` 프로필에서 등록을 끄므로 `ORG_SERVICE_URI`/`SEARCH_SERVICE_URI`로 DNS 직결한다. `wiki-backend`도 `WIKI_SERVICE_URI`로 직결한다. 이 세 env가 빠지면 `lb://` 기본값이 해석될 방법이 없어 해당 경로가 통째로 503이 된다.

**요청 처리 순서:** `PatExchangeWebFilter`(order -101, PAT→JWT 헤더 치환) → Security(CORS 프리플라이트 응답·JWT 검증·401 조기차단, `WebFilterChainProxy` order -100) → `RequestLoggingFilter`(`HIGHEST_PRECEDENCE` GlobalFilter — 401로 잘린 요청은 여기 안 옴) → 라우트 매칭(rate limit·CB 필터) → 다운스트림 프록시.

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
| `com.github.ben-manes.caffeine:caffeine` | PAT 교환 결과 인스턴스 로컬 캐시 |
| `spring-boot-starter-webflux` (test only) | WebTestClient |
| `com.squareup.okhttp3:mockwebserver` (test only) | PAT 교환 테스트용 auth-server 스텁 |

> Boot 4 / Spring Cloud 2025.x 기준 설정 prefix는 `spring.cloud.gateway.server.webflux.*` 다. 구버전 문서의 `spring.cloud.gateway.routes` 예시를 그대로 붙여넣으면 라우트가 조용히 무시되니 주의.

---

## 빠른 시작

**전제:** eureka-server(:8761) · auth-server(:9000) 등 라우팅 대상이 떠 있어야 실제 프록시가 성립한다. (게이트웨이 자체는 아무것도 없이도 뜨지만, 프록시 요청은 인스턴스 미발견으로 503이 난다. 기동 순서는 강제 아님 — 서비스가 유레카에 등록되는 대로 라우팅이 살아난다.)

### gradlew (터미널)

```powershell
# Windows PowerShell — JDK 24 필요(기본 JDK가 11이면 실패)
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
.\gradlew.bat bootRun        # :8000 기동
```

### IntelliJ (.run 공유 Config)

repo에 커밋된 `.run/` 공유 Run Config가 IntelliJ 실행 버튼에 자동으로 나타난다(`bootRun`, dev 오프셋용 `bootRun (dev)`). 백엔드 공통 개발 방식이며 프론트는 VSCode + `scripts/dev-up-local.ps1`을 쓴다(우산 repo README 참고).

기동 확인: `http://localhost:8000/.well-known/jwks.json` → auth-server JWKS 그대로 반환.

> 포트 맵: gateway 8000 / eureka 8761 / auth 9000 / board 9100 / wiki 9110(gRPC 9111) / alm 9120(gRPC 9121) / org 9130(gRPC 9131) / search 9140 / Keycloak 8080 / Postgres 5433 / Redis 6379 / myFront 5173.
> dev 오프셋 클러스터는 운영 포트 **+10000**(게이트웨이 dev = `:18000`).

### Docker (컨테이너)

`Dockerfile`은 **런타임 전용**이다 — jar를 빌드하지 않고 이미 만들어진 `build/libs/app.jar`를 복사한다. 먼저 `gradlew bootJar`로 jar를 만든 뒤 이미지를 빌드한다.

```powershell
.\gradlew.bat bootJar                    # build/libs/app.jar 생성
docker build -t gateway-server .          # eclipse-temurin:24-jre 베이스, EXPOSE 8000
```

Compose는 `EUREKA_URI`와 `ORG_SERVICE_URI`/`WIKI_SERVICE_URI`/`ALM_SERVICE_URI`/`SEARCH_SERVICE_URI`를 서비스명 기준으로 주입한다. auth·board 라우트에는 직접 URI를 주입하지 않고 컨테이너에서도 `lb://` + Eureka를 사용한다(아래 환경 변수 표).

---

## 라우팅 규칙

`application.yml`에 선언. **기본은 No StripPrefix — 클라이언트가 보낸 경로 = 서비스가 받는 경로.** (`search`만 예외, 아래)
대상 기본값은 전부 `lb://<서비스명>`이다. 컨테이너에서는 auth·board가 그대로 유레카로 해석되고, org·wiki·alm·search만 `*_SERVICE_URI` env로 DNS 직결된다(아래 표의 굵은 표시).

| 라우트 id | 경로 패턴 | → 대상 (기본값 / docker env) | 필터 / 비고 |
|---|---|---|---|
| `board` | `/api/board/**` | `lb://board-service` (docker도 유레카) | CircuitBreaker(→`/fallback/board`) + GET 한정 Retry 2회 |
| `auth-oauth2` | `/oauth2/**` | `lb://auth-server` / `AUTH_SERVER_URI` | OIDC 로그인 시작·콜백. RateLimiter 10/20 |
| `auth-login` | `/login/**` | `lb://auth-server` / `AUTH_SERVER_URI` | OIDC 리다이렉트 흐름. RateLimiter 10/20 |
| `auth-api` | `/api/auth/**` | `lb://auth-server` / `AUTH_SERVER_URI` | refresh · logout. RateLimiter 5/10 |
| `auth-jwks` | `/.well-known/**` | `lb://auth-server` / `AUTH_SERVER_URI` | JWKS 공개키. 필터 없음 |
| `auth-me` | `/api/me` | `lb://auth-server` / `AUTH_SERVER_URI` | 자체 JWT 기반 사용자 정보. RateLimiter 없음(의도 — Security가 무토큰/위조를 이미 401 차단, 브루트포스 표면 아님) |
| `org` | `/api/org/**` | `lb://org-service` / **docker: `ORG_SERVICE_URI`** | 조직·팀·RBAC. 경로 불변 |
| `wiki-collaboration` | `/api/wiki/collaboration[/**]` | `ws://localhost:19150` / **docker: `COLLABORATION_SERVICE_URI`** | 1회 ticket 인증 Hocuspocus WebSocket. wiki보다 우선 |
| `wiki` | `/api/wiki/**` | `lb://wiki-backend` / **docker: `WIKI_SERVICE_URI`** | 스페이스·페이지·첨부. 경로 불변 |
| `alm` | `/api/alm/**` | `lb://alm-backend` / **docker: `ALM_SERVICE_URI`** | 프로젝트·이슈. 경로 불변 |
| `agent` | `/api/agent/**` | `lb://agent-service` / **docker: `AGENT_SERVICE_URI`** | 에이전트 REST·MCP. 경로 불변. `/api/agent/mcp/**`만 게이트웨이 permitAll(서비스가 자체 인증) |
| `search` | `/api/search/**` | `lb://search-service` / **docker: `SEARCH_SERVICE_URI`** | **`StripPrefix=2`** + RateLimiter 5/15 |

### `search` 라우트의 StripPrefix=2 (No StripPrefix 원칙의 유일한 예외)

org/wiki/alm은 컨트롤러가 `/api/org`·`/api/wiki`·`/api/alm` 접두사를 그대로 갖고 있어 경로를 손대지 않는다.
search-service는 갖지 않는다 — GraphQL이 단일 URL이라 접두사를 붙일 자리가 없고, 관리 REST도 그에 맞춰 통일했다. 그래서 이 라우트만 앞 두 세그먼트(`api`, `search`)를 뗀다.

| 외부 경로(클라이언트) | 내부 경로(search-service가 받는 것) |
|---|---|
| `POST /api/search/graphql` | `POST /graphql` |
| `POST /api/search/admin/reindex` | `POST /admin/reindex` |
| `GET /api/search/admin/reindex/{jobId}` | `GET /admin/reindex/{jobId}` |

`parts` 값이 틀리면 다운스트림이 조용히 404를 낸다. 값을 바꾸면 `RouteConfigTest`의 `StripPrefix parts = 2` 단언이 깨진다.

**rate limit을 거는 이유**: 검색은 한 요청이 색인 전체를 훑을 수 있어 비용이 크다. Security가 무인증을 이미 401로 끊지만, 유효 토큰 소지자가 반복해 때리는 것까지는 막지 못한다. `auth-api`(5/10)보다 burst만 넉넉한 **5/15** — 사람이 타이핑하며 재검색하는 패턴은 통과시키되 스크립트 연타는 자른다. Redis 부재 시 fail-open은 다른 라우트와 동일하다.

라우트 id·`lb://` 기본값·필터 구성은 `RouteConfigTest`가 검증하므로, 바꾸면 테스트도 함께 갱신한다.
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
| `EUREKA_URI` | `http://localhost:8761/eureka` | `http://eureka:8761/eureka` |
| `AUTH_SERVER_URI` | `lb://auth-server` (유레카 해석) | **compose는 주입하지 않는다** — 컨테이너에서도 유레카로 찾는다 |
| `BOARD_SERVICE_URI` | `lb://board-service` (유레카 해석) | **compose는 주입하지 않는다** — 컨테이너에서도 유레카로 찾는다 |
| `ORG_SERVICE_URI` | `lb://org-service` (유레카 해석) | `http://org-service:9130` (**docker 필수** — 유레카 미등록) |
| `WIKI_SERVICE_URI` | `lb://wiki-backend` (유레카 해석) | `http://wiki-backend:9110` (compose가 주입) |
| `COLLABORATION_SERVICE_URI` | `ws://localhost:19150` | `ws://collaboration-service:9150` |
| `ALM_SERVICE_URI` | `lb://alm-backend` (유레카 해석) | `http://alm-backend:9120` (**docker 필수** — 유레카 미등록) |
| `AGENT_SERVICE_URI` | `lb://agent-service` (유레카 해석) | `http://agent-service:9160` |
| `SEARCH_SERVICE_URI` | `lb://search-service` (유레카 해석) | `http://search-service:9140` (**docker 필수** — 유레카 미등록) |
| `CORS_ALLOWED_ORIGIN` | `http://localhost:5173` | `http://localhost:5173` |
| `AUTH_JWKS_URI` | `http://localhost:9000/.well-known/jwks.json` | `http://auth-server:9000/...` |
| `PLATFORM_ISSUER` | `http://localhost:9000` | auth-server 발급 iss와 일치 |
| `PLATFORM_AUDIENCE` | `platform-api` | auth-server 발급 aud와 일치 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | `redis` / `6379` (없으면 rate limit fail-open) |
| `AUTH_SERVER_BASE_URI` | `http://localhost:9000` (dev 프로필 `http://localhost:19000`) | `http://auth-server:9000` (docker 프로필 기본값) — PAT 교환 호출 대상 |
| `AGENT_INTERNAL_SECRET` | **없음(빈 값)** → PAT 전부 401 | auth-server와 **같은 값**을 양쪽에 주입 (`openssl rand -hex 32`) |

**Docker DNS 설명:** Docker Compose 네트워크 안에서는 `docker-compose.yml`의 **서비스명이 곧 DNS 호스트명**이 된다. 컨테이너끼리 `auth-server`, `wiki-backend`, `search-service` 등으로 직접 찾을 수 있어서, 환경변수에 `http://search-service:9140` 처럼 서비스명을 사용한다. 로컬 직접 실행 시에는 기본값인 `localhost`가 적용된다. (`CORS_ALLOWED_ORIGIN`은 **브라우저 주소창 기준** 오리진이므로 컨테이너 안에서도 `localhost:5173` 그대로다.)

`org-service`·`wiki-backend`·`alm-backend`·`search-service`는 `docker` 프로필에서 **유레카에 등록하지 않는다**(확정 설계). 따라서 네 `*_SERVICE_URI`는 컨테이너 배포에서 선택이 아니라 **필수**다 — 빠지면 `lb://` 기본값을 해석할 방법이 없어 해당 라우트 전체가 503이 된다.

반대로 `auth-server`·`board-service`는 컨테이너에서도 유레카에 등록되므로 `AUTH_SERVER_URI`/`BOARD_SERVICE_URI`를 주입하지 않는다 — compose의 게이트웨이 env를 보면 이 둘은 없고 `EUREKA_URI`만 있다. **즉 컨테이너 스택에서 eureka는 여전히 필수 구성요소다.**

**게이트웨이는 제품 서비스를 `depends_on`에 두지 않는다** — 게이트웨이가 org·wiki·alm·search의 기동을 기다리면 단일 진입점이 그 서비스와 함께 죽는다. 다운스트림 부재는 라우트 단위 5xx로 격리되는 것이 설계다.

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

## 개인 API 토큰(PAT) 교환 — `PatExchangeWebFilter`

`filter/PatExchangeWebFilter.java` — 순수 `org.springframework.web.server.WebFilter`, **`@Order(-101)`**.

**왜 `GlobalFilter`가 아닌가.** SCG의 `GlobalFilter`는 보안 체인(`WebFilterChainProxy`, order **-100**) *뒤*에 실행된다. PAT는 JWT가 아니므로 그 자리에 오기 전에 이미 401로 잘린다. 그래서 -101의 `WebFilter`로 보안 체인보다 한 칸 앞에 세운다. 이 순서가 이 기능의 유일한 성립 조건이라 `PatExchangeFilterOrderTest`가 실제 컨텍스트의 `List<WebFilter>` 인덱스로 회귀를 막는다.

동작:

1. `Authorization: Bearer chanho_pat_…`(스킴 대소문자 무시)일 때만 개입한다. 일반 JWT Bearer·헤더 없는 요청은 **손대지 않고** 통과시킨다.
2. 캐시(Caffeine, 키 = `hex(sha256(원문토큰))`) 조회 → 히트면 헤더를 `Bearer <jwt>`로 치환하고 체인 계속.
3. 미스면 `POST {AUTH_SERVER_BASE_URI}/internal/pat/exchange`(헤더 `X-Internal-Secret`, 본문 `{"token":"…"}`), 타임아웃 **2s**.

| auth-server 응답 | 게이트웨이 | 캐시 |
|---|---|---|
| 200 `{accessToken, expiresInSeconds}` | 헤더를 `Bearer <jwt>`로 치환 후 체인 계속 | 성공 60s (단 JWT 만료 **30초 전**까지만 재사용) |
| 401 `{"error":"invalid_token"}` | **401** `{"error":"invalid_token"}` (application/json, UTF-8) | 부정 10s — 무차별 대입이 매 요청 auth-server를 때리지 못하게 |
| 403(비밀 불일치) · 5xx · 타임아웃 · 연결 실패 | **503** `{"error":"auth_unavailable"}` | 안 함 — 장애는 곧 풀릴 수 있고 그 사이 정상 토큰을 막을 이유가 없다 |

**fail-closed 두 가지.**
- `AGENT_INTERNAL_SECRET`이 비어 있으면 교환을 시도조차 하지 않고 PAT 요청을 **전부 401**로 거부한다. 기동 시 WARN 한 줄이 남는다. 비밀 없이 열어두면 인증이 통째로 무력화되므로 "동작 안 함"이 옳은 실패다.
- 403(비밀 불일치)을 401로 접지 않는다. 배포 설정 오류를 "네 토큰이 틀렸다"로 바꾸면 정상 토큰이 부정 캐시에 들어간다.

**캐시는 Redis가 아니라 Caffeine 인스턴스 로컬이다.** rate limiter는 Redis 부재 시 fail-open이어도 되지만(요청 통과), 인증 캐시가 같은 성질을 가지면 그대로 취약점이다. 대가는 폐기 반영 지연 — 토큰을 폐기해도 최대 60초(캐시 TTL) 동안 통과할 수 있고, 게이트웨이 인스턴스가 여러 개면 인스턴스마다 따로 캐시한다. 즉시 차단이 필요하면 `POSITIVE_TTL`을 줄인다.

**로그에 원문 토큰을 남기지 않는다** — 해시 앞 8자만 `tokenHash=`로 남긴다. auth-server 불능은 `error`(운영 알람), 토큰 거부는 `warn`.

**CORS 주의**: 이 필터는 Security의 CORS 처리보다 앞이라 필터가 직접 쓰는 401/503 응답에는 CORS 헤더가 붙지 않는다. PAT는 스크립트·CI용이고 브라우저(myFront)는 세션 JWT를 쓰므로 실사용 영향은 없다. 브라우저에서 PAT를 직접 쓰게 되면 이 지점을 먼저 손봐야 한다.

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

전체 테스트는 실제 다운스트림으로 프록시하지 않으므로 auth/board/org/wiki/alm/search·Redis 없이 돈다.

| 테스트 | 개수 | 검증 내용 |
|---|---|---|
| `RouteConfigTest` | 7 | 라우트 id 13개 등록(`agent` 포함) + 기본 uri가 `lb://`(제품 4서비스 포함) + 인증 라우트의 RequestRateLimiter + **`search`만 `StripPrefix parts = 2`이고 org/wiki/alm에는 없음** + search 라우트의 rate limiter |
| `CorsTest` | 1 | `:5173` 오리진의 프리플라이트(OPTIONS)가 200 + `Access-Control-Allow-Origin` 응답 |
| `SecurityConfigTest` | 8 | 보호 경로 무토큰/위조 토큰 401(+401에도 CORS 헤더), 공개 경로는 보안 통과, **`/api/search/graphql`·`/api/search/admin/reindex[/{id}]` 무토큰 401**, `/api/agent/**` 무토큰 401 · `/api/agent/mcp/**`는 보안 통과 |
| `AudienceValidatorTest` | 3 | aud 클레임 일치/불일치 검증 |
| `BoardFallbackTest` | 2 | board 다운 시 fallback 503 JSON |
| `SlowBoardDownstreamTest` | 1 | 느린 다운스트림에서 타임아웃/CB 동작 |
| `HttpClientTimeoutTest` | 1 | 전역 connect/response 타임아웃 바인딩 |
| `IpKeyResolverTest` | 4 | rate limit 키 = nginx 뒤 XFF 실 클라이언트 IP (없으면 "unknown") |
| `PatExchangeWebFilterTest` | 13 | MockWebServer로 auth-server를 세우고 필터 단독 검증 — PAT→다운스트림이 보는 `Bearer <jwt>`, 캐시 히트 시 auth-server 미호출, JWT 만료 30초 가드, 401 부정 캐시, 5xx/403/타임아웃 → 503(캐시 안 함), 비밀 미설정 시 호출 없이 401, JWT·무헤더·Basic 무변경 |
| `PatExchangeFilterOrderTest` | 2 | 실제 컨텍스트의 `List<WebFilter>`에서 PAT 필터가 `WebFilterChainProxy`보다 앞 + PAT 요청이 Security가 아닌 필터에게 거부됨(본문 `invalid_token`) |
| `RequestLoggingFilterTest` | 4 | `X-Request-Id` 생성/보존/형식 검증 후 재발급 + 헤더 1개 유지 |

빌드 산출물: `bootJar` → `build/libs/app.jar` (archiveFileName 고정, Dockerfile이 이 이름으로 복사).

---

## 확장 포인트 (미구현 — 위치만 표시)

| 기능 | 구현 방향 |
|---|---|
| **분산 추적 백엔드** | Micrometer Tracing + Zipkin/Tempo exporter (수동 X-Request-Id 대체) |
| **요청 크기 제한 / 보안 응답 헤더** | `RequestSize` 필터, SecureHeaders |

> Rate Limiting·서킷브레이커·JWT 조기차단, 서비스 디스커버리(Eureka `lb://`), nginx 뒤 XFF 실 IP rate-limit 키는 모두 구현 완료다(위 역할 섹션 참고).

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
└─ filter/
   ├─ RequestLoggingFilter.java   GlobalFilter: X-Request-Id 검증/재발급 + 요청 1줄 로깅
   ├─ PatExchangeWebFilter.java   WebFilter(order -101): PAT→JWT 헤더 치환 + Caffeine 캐시
   ├─ PatExchangeClient.java      auth-server /internal/pat/exchange 호출(2s 타임아웃)
   └─ PatExchangeResult.java      성공/무효/불능 3분기 sealed 결과
src/main/resources/
├─ application.yml                  라우트 10개(+rate limit·CB·StripPrefix 필터) + trusted-proxies + 타임아웃 + platform.* 계약
├─ application-dev.yml              dev 오프셋(:18000, Redis db1, dev eureka/JWKS)
└─ application-docker.yml           컨테이너 프로필 — 콘솔 로그를 ECS JSON으로(Alloy→Loki 수집)
.run/                               IntelliJ 공유 Run Config (bootRun / bootRun (dev))
Dockerfile                          런타임 전용 (temurin:24-jre, build/libs/app.jar 복사)
```

## 트러블슈팅

- **`Gradle requires JVM 17 or later`** → `JAVA_HOME`을 JDK 24로. (기본이 11)
- **라우트가 전혀 안 먹고 전부 404** → 설정 prefix 확인. Boot 4 / Spring Cloud 2025.x는 `spring.cloud.gateway.server.webflux.routes` 다(구버전 `spring.cloud.gateway.routes` 아님).
- **nginx 통합배포에서 로그인 시 Keycloak이 `redirect_uri` 거부** → 게이트웨이 `trusted-proxies`에 프록시/도커 대역이 포함됐는지 확인(위 X-Forwarded 섹션). Keycloak realm의 Valid Redirect URIs가 stale하지 않은지도 함께 점검(2중 원인).
- **프록시 응답이 `503 Service Unavailable`** → auth·board는 미기동 또는 Eureka 등록 전파 대기(최대 30s)일 수 있으므로 `http://localhost:8761` 대시보드를 확인한다. org·wiki·alm·search는 DNS 직결이므로 게이트웨이의 `ORG_SERVICE_URI`/`WIKI_SERVICE_URI`/`ALM_SERVICE_URI`/`SEARCH_SERVICE_URI`와 대상 컨테이너의 running·healthy 상태를 확인한다.
- **프록시 응답이 `500 UnknownHostException ... mshome.net`** → 다운스트림이 유레카에 DNS 해석 불가 호스트명으로 등록된 것. 각 서비스 `eureka.instance.prefer-ip-address: true` 확인 (Windows/Hyper-V에서 필수 — E2E 실측).
- **다운스트림이 `Port 910x was already in use`로 기동 실패 (리스너 없음)** → Docker/Hyper-V가 부팅 시 예약한 포트 제외 범위에 걸린 것. `netsh interface ipv4 show excludedportrange protocol=tcp`로 확인 후 범위 밖 포트로 기동(`$env:SERVER_PORT`). 유레카 덕에 포트를 바꿔도 게이트웨이 설정은 불변.
- **브라우저 콘솔에 CORS 에러** → 프론트 오리진이 `CORS_ALLOWED_ORIGIN`과 정확히 일치하는지(스킴·호스트·포트) 확인. 쿠키가 필요한 요청은 프론트에서 `credentials: 'include'`도 필요.
- **다운스트림에서 CORS 헤더가 중복** → auth-server/board-service에 CORS 설정이 남아 있는 것. 다운스트림 CORS는 전부 제거해야 한다(게이트웨이 단일 책임).
- **PAT가 항상 401 `invalid_token`** → 게이트웨이에 `AGENT_INTERNAL_SECRET`이 없다(기동 로그의 WARN 확인). auth-server와 **같은 값**을 양쪽에 주입해야 한다. 값이 서로 다르면 401이 아니라 503 `auth_unavailable`이 난다.
- **PAT가 503 `auth_unavailable`** → auth-server 불능이거나 비밀 불일치. 게이트웨이 로그의 `reason=`을 본다(`status_403`=비밀 불일치, `status_5xx`=auth-server 오류, `TimeoutException`=2s 초과).
- **토큰을 폐기했는데 잠깐 더 통과한다** → 정상이다. 성공 캐시 60s + 교환 JWT 300s 설계상 최대 60초 지연된다.
- **`:8000` 기동 실패(Address already in use)** → 이전 gateway 프로세스가 살아 있음. `netstat -ano | findstr :8000` 후 해당 PID 종료.
