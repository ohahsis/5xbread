# 5xbread

빵 예약 서비스 백엔드

### 로컬 실행 방법

1. `cp .env.example .env` 후 `JWT_SECRET`, `PICKUP_SECRET`, `ADMIN_INITIAL_PASSWORD` 값을 채움
2. `docker compose up -d --build` → MySQL, Redis, app1~app3, Nginx가 뜨고 Flyway가 테이블·초기 데이터(빵 25종, 판매일 7일치, 사장님 계정, 테스트 손님 1,000명)를 넣음
3. 손님 화면 `http://localhost`, 사장님 화면 `http://localhost/admin`, API 문서 `http://localhost/swagger-ui`
4. 모니터링이 필요하면 `docker compose --profile monitoring up -d` → Grafana `http://localhost:3000`
5. 부하 테스트 `k6 run load/reserve.js`