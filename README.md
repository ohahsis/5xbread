# 5XBread

동네 빵집의 빵을 판매일·픽업 시간대별로 미리 예약하는 서비스의 백엔드입니다. 앱 3개를 동시에 띄운 환경에서도 **초과 예약 0건**을 지키는 것이 핵심입니다.

- 확정한 결정·범위: [`docs/decisions.md`](docs/decisions.md) (정의서와 다르면 이 문서가 우선)
- 엣지 케이스 E-01~E-15와 증명하는 테스트: [`docs/edge-cases.md`](docs/edge-cases.md)

## 스택

Java 21, Spring Boot 3.5.16, Gradle 8.14(Kotlin DSL), MySQL 8, Redis 7, Flyway, Spring Security + JWT, STOMP WebSocket, Caffeine, ShedLock, Nginx, Testcontainers, k6(부하 확인), GitHub Actions

## 구조

```
                         ┌──────────── Nginx :80 (라운드 로빈, /ws 업그레이드) ────────────┐
                         ▼                          ▼                          ▼
                       app1                       app2                       app3     (같은 이미지, 상태 없음)
                         │  Caffeine 30초 (판매일 설정·카탈로그)                    │
          ┌──────────────┴──────────────────────────┬───────────────────────────────┘
          ▼                                         ▼
   Redis 7 (AOF)                              MySQL 8 (KST)
   · 재고·1인 누적: Lua로 검사+차감 한 번에        · 조건부 UPDATE로 최종 확정
   · Idempotency, 로그인 잠금, Refresh Token     · outbox_event (취소 복구·수량 늘리기)
   · Pub/Sub: 캐시 무효화, 잔여 변경(C-03)        · Flyway V1·V2
   · ShedLock (스케줄 작업은 앱 1개에서만)
```

**예약 한 건의 흐름** (`reservation` 패키지, Controller → Facade → Service → Domain → Repository)

1. Redis Lua 한 번으로 모든 빵의 잔여·1인 한도를 검사하고 전부 통과하면 함께 차감 (하나라도 부족하면 아무것도 안 바뀜)
2. DB 트랜잭션에서 `reserved_qty + n <= reservable_qty` 조건부 UPDATE와 예약 저장. 트랜잭션 안에서는 Redis를 부르지 않는다
3. DB가 실패하면 1의 차감을 같은 Lua로 되돌린다(보상). 0행이면 409 `OUT_OF_STOCK`
4. 잔여를 바꾼 Lua가 같은 스크립트에서 버전을 올리고 발행 → 앱마다 200ms씩 모아 STOMP로 구독자에게

취소·직권 취소의 재고 복구와 수량 늘리기는 트랜잭셔널 아웃박스로 커밋과 함께 남기고, 5분마다 정합성 점검이 DB와 Redis의 차이를 고칩니다(같은 차이가 연속 2번 보일 때만).

패키지: `common` `auth` `member` `catalog` `saledate` `reservation` `realtime` `admin` `batch`

## 실행

먼저 `cp .env.example .env` 후 필수 값(`DB_PASSWORD`, `DB_ROOT_PASSWORD`, `JWT_SECRET`, `PICKUP_SECRET`, 각 비밀 키는 32바이트 이상)과 사장님 초기 계정을 채웁니다. 선택 값은 `.env.example`의 주석을 보세요.

### 1. 로컬 (앱 1개)

```bash
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'
```

- `local` 프로필이 루트의 `compose.yaml`로 MySQL·Redis 컨테이너를 띄웁니다(직접 띄우려면 루트에서 `docker compose up -d`).
- Flyway가 테이블과 초기 데이터(빵 25종, 내일부터 판매일 7일치)를 넣고, 사장님 계정이 없으면 `.env` 값으로 만듭니다(첫 로그인 때 비밀번호 변경).
- API `http://localhost:8080/api/v1/...`, API 문서 `http://localhost:8080/swagger-ui.html`, 실시간 잔여 STOMP `ws://localhost:8080/ws`
- 테스트: `cd backend && ./gradlew build` (Testcontainers로 MySQL·Redis를 따로 띄움)

### 2. 앱 3개 + Nginx

```bash
docker compose -f compose.full.yaml up -d --build --wait
# http://localhost (Nginx → app1~3)
docker compose -f compose.full.yaml down        # 데이터까지 지우려면 -v
```

개발용 `compose.yaml`과 프로젝트 이름·볼륨이 달라 함께 띄워도 섞이지 않습니다. MySQL·Redis 포트는 호스트에 열지 않습니다. 앱 1개당 메모리 768MB(힙 75%).

### 3. 부하·정합성 확인 (앱 3개를 띄운 뒤, 루트에서)

| 명령 | 하는 일 | 판정 (하나라도 틀리면 exit 1) |
|---|---|---|
| `./load/run.sh` | 테스트 손님 1,000명(`load/seed.sql`)이 재고 120개인 빵을 동시에 1개씩 예약 | 초과 예약 0, DB 예약 수량 = 예약 합, Redis 잔여 = DB 잔여, 201 수 = 예약 수량, 5xx 0 |
| `./load/run.sh --with-cancel` | 같은 예약에 취소와 다시 예약을 섞음 | 위 판정 + 취소 200 = DB 취소 건수, 201 − 취소 = 예약 수량, 아웃박스 미발행 0 |
| `./load/realtime-check.sh` | STOMP 리스너 6개(앱 3개에 나뉨) + 예약 3건 | 모든 리스너가 버전 1·2·3을 순서대로 받고, 마지막 잔여 = Redis = 목록, 다른 앱이 처리한 예약도 받음 |

테스트 손님은 운영 마이그레이션에 넣지 않고 이 스크립트들만 넣습니다(판매일은 10일 뒤).

## 측정값 (1차 마무리, 2026-11-16, main `3849e0b`)

환경: Mac(Intel i7-9750H 6코어 12스레드, 메모리 32GB), macOS 26.7, Docker Desktop(VM 12 CPU, 8GB). 앱 3개는 각 768MB, MySQL·Redis·Nginx·k6가 같은 머신에서 함께 돕니다. 첫 실행(워밍업)을 버리고 측정했습니다.

| 시나리오 | 결과 | 응답 시간 |
|---|---|---|
| `run.sh` 동시 예약 1,000건 | 201 **120** / 409 880 / 5xx **0**, 초과 예약 0, Redis = DB. 앱별 336 · 333 · 331건 | 예약 중앙값 2.69s, p95 3.40s / 로그인 p95 8.24s |
| `run.sh --with-cancel` | 201 138 / 409 862, 취소 200 71건, 다시 예약 201 53건 → 최종 예약 120 = 재고, 5xx 0, 아웃박스 미발행 0 | 예약 중앙값 2.31s, p95 5.23s |
| `realtime-check.sh` | 리스너 6개 모두 101·오류 0, 버전 1·2·3, 잔여 117 = Redis, 예약을 처리한 앱 3개 전부에서 전파 | |

응답 시간은 노트북 한 대에서 1,000명이 같은 순간에 로그인(BCrypt)과 예약을 몰아 보낸 값이라 처리량 지표가 아닙니다. 이 측정이 보이는 것은 정합성입니다. 로그인이 느린 이유와 BCrypt 동시 계산 제한은 decisions.md 6장 "재시작 직후 로그인 폭주"에 있습니다.

## 1차 범위

decisions.md 4장이 기준입니다. 요약:

- **손님**: 가입·로그인(JWT + Refresh rotation, 실패 잠금), 판매일·빵 목록(Redis 잔여 + Caffeine), 예약(Idempotency-Key, 요청 제한), 내 예약 목록(커서)·상세, 취소, 픽업 QR 토큰(HMAC), 비밀번호 변경, 실시간 잔여(STOMP, 서버만)
- **사장님**: 빵 등록·수정·판매 중지, 판매일·픽업 시간대 설정, 수량 설정(ETag/If-Match), 수령 처리·검색·QR 검증, 직권 취소, 임시 비밀번호, 예약 제한 해제, 수량 재계산
- **배치·복구**: 노쇼 처리(매일 21:00), 트랜잭셔널 아웃박스, 정합성 점검(5분), 모두 ShedLock으로 앱 1개에서만
- **테스트**: 동시 요청 테스트 포함 JUnit + Testcontainers, E-01~E-15, 시간대 고정 테스트, 앱 3개 스크립트 3종

## 2차로 미룬 것

프론트엔드 전체(손님·사장님 화면, 실시간 잔여 화면 포함), A-03 수량 복사, A-05 대시보드 실시간·픽업 준비 목록 인쇄, A-11 판매 통계, 탈퇴, 사진 WEBP 변환, k6 부하 테스트 고도화와 v1·v2 비교, Prometheus·Grafana, 아웃박스 실패 이벤트 dead letter, 오래된 데이터 아카이브. 전체 목록과 근거는 decisions.md 4장 "2차로 미룸"에 있습니다.
