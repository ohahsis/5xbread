#!/usr/bin/env bash
# load/run.sh : 앱 3개 + Nginx 환경에서 동시 예약 1,000건 실행과 정합성 확인 (N-21)
# 먼저 앱 3개를 띄운다: docker compose -f compose.full.yaml up -d --build
# 실행(레포 루트에서): ./load/run.sh
#
# 1) 테스트 판매일(10일 뒤)의 이전 결과를 지우고 seed.sql 로 손님 1,000명·판매일·재고를 다시 넣는다
# 2) 그 판매일의 Redis 키를 지운다 (첫 예약 때 DB 기준으로 다시 적재된다)
# 3) k6 컨테이너로 Nginx 주소에 동시 예약 1,000건
# 4) DB·Redis 값을 비교해 출력한다
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f compose.full.yaml)
BREAD_ID=1

# 비밀번호는 컨테이너 안의 환경 변수로만 쓴다 (명령줄·출력에 남기지 않음)
sql() {
  "${COMPOSE[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ufivexbread -N -B fivexbread'
}
redis() {
  "${COMPOSE[@]}" exec -T redis redis-cli "$@"
}

SALE_DATE=$(echo "SELECT CURDATE() + INTERVAL 10 DAY;" | sql)
echo "판매일 $SALE_DATE"

PICKUP_SLOT_ID=$({ echo "SET @d = '$SALE_DATE';"; cat load/seed.sql; } | sql | tail -1)
# 키 목록과 삭제를 컨테이너 안에서 한 번에 한다 (밖에서 while read 로 돌리면 docker compose exec 가 나머지 키 목록을 stdin 으로 먹는다)
"${COMPOSE[@]}" exec -T redis sh -c 'redis-cli --scan --pattern "$1" | xargs -r redis-cli DEL > /dev/null' _ "*{$SALE_DATE}*"

APP_IPS=$(for app in app1 app2 app3; do
  id=$("${COMPOSE[@]}" ps -q "$app")
  echo -n "$app=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$id"),"
done)

"${COMPOSE[@]}" run --rm --no-deps --quiet-pull \
  -e SALE_DATE="$SALE_DATE" -e PICKUP_SLOT_ID="$PICKUP_SLOT_ID" -e BREAD_ID="$BREAD_ID" -e APP_IPS="$APP_IPS" \
  k6 run --quiet /load/reserve.js 2>&1 | grep -v -E "^ ?Container "

echo
echo "== DB =="
sql <<SQL
SELECT 'reservable_qty', reservable_qty FROM daily_stock WHERE sale_date = '$SALE_DATE' AND bread_id = $BREAD_ID
UNION ALL
SELECT 'reserved_qty', reserved_qty FROM daily_stock WHERE sale_date = '$SALE_DATE' AND bread_id = $BREAD_ID
UNION ALL
SELECT 'sum(reservation_item.quantity)', COALESCE(SUM(i.quantity), 0)
FROM reservation r JOIN reservation_item i ON i.reservation_id = r.id
WHERE r.sale_date = '$SALE_DATE' AND i.bread_id = $BREAD_ID AND r.status <> 'CANCELED'
UNION ALL
SELECT 'reservations', COUNT(*) FROM reservation WHERE sale_date = '$SALE_DATE'
UNION ALL
SELECT 'members_with_2+_reservations', COUNT(*) FROM (
  SELECT member_id FROM reservation WHERE sale_date = '$SALE_DATE' GROUP BY member_id HAVING COUNT(*) > 1) t;
SQL

echo "== Redis =="
echo "stock:{$SALE_DATE}:$BREAD_ID = $(redis GET "stock:{$SALE_DATE}:$BREAD_ID")"
