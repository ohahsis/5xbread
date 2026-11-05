# 5xbread

빵 예약 서비스 백엔드

### 로컬 실행 방법 (앱 1개)

1. `cp .env.example .env` 후 `DB_PASSWORD`, `DB_ROOT_PASSWORD`, `JWT_SECRET`(UTF-8 32바이트 이상), `ADMIN_INITIAL_PHONE`, `ADMIN_INITIAL_PASSWORD` 값을 채움 (`PICKUP_SECRET`은 아직 쓰지 않음)
2. `cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'`
   - `local` 프로필이 루트의 `compose.yaml`로 MySQL·Redis 컨테이너를 자동으로 띄움 (직접 띄우려면 루트에서 `docker compose up -d`)
   - Flyway가 테이블과 초기 데이터(빵 25종, 내일부터 판매일 7일치)를 넣고, 사장님 계정이 없으면 `.env` 값으로 만듦
3. API `http://localhost:8080/api/v1/...`, API 문서 `http://localhost:8080/swagger-ui.html`
4. 테스트 `cd backend && ./gradlew build` (Testcontainers로 MySQL·Redis를 따로 띄움)

프론트엔드 화면, 모니터링(Grafana)은 아직 없습니다. 테스트 손님 1,000명은 개발 DB에 넣지 않고, 아래 앱 3개 환경의 부하 테스트(`load/run.sh`)에서만 넣습니다.

### 앱 3개로 실행하기

MySQL·Redis·app1~app3·Nginx(`http://localhost`)를 띄우고, 손님 1,000명이 같은 빵을 동시에 예약합니다. `run.sh`가 초과 예약 0건, DB 예약 수량 = 예약 합계, Redis 잔여 = DB 잔여, 5xx 0건을 판정하고 하나라도 틀리면 exit 1로 끝납니다. `.env`가 먼저 채워져 있어야 합니다.

```bash
docker compose -f compose.full.yaml up -d --build --wait
./load/run.sh
docker compose -f compose.full.yaml down        # 데이터까지 지우려면 -v
```
