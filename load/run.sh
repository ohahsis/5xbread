#!/usr/bin/env bash
# load/run.sh : 앱 3개 + Nginx 환경에서 동시 예약 1,000건을 보내고 정합성을 판정한다 (N-21)
# 먼저 앱 3개를 띄운다: docker compose -f compose.full.yaml up -d --build --wait
# 실행(레포 루트에서): ./load/run.sh              → 하나라도 틀리면 exit 1
#                     ./load/run.sh --check-only → 데이터를 다시 넣거나 요청을 보내지 않고, 지금 DB·Redis 만 판정
#                     ./load/run.sh --with-cancel → 예약과 취소를 섞는다(reserve.js CANCEL=1). 아웃박스 미발행 행 0 도 판정
#
# 1) 테스트 판매일(10일 뒤)의 이전 결과를 지우고 seed.sql 로 손님 1,000명·판매일·재고를 다시 넣는다
# 2) 그 판매일의 Redis 키를 지운다 (첫 예약 때 DB 기준으로 다시 적재된다)
# 3) k6 컨테이너로 Nginx 주소에 동시 예약 1,000건
# 4) 판정: reserved_qty = 예약 수량 합 ≤ reservable_qty, Redis 잔여 = reservable_qty − reserved_qty,
#    응답은 201 개수 = reserved_qty(1건당 1개), 5xx 0, 1,000건 모두 201 또는 409
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f compose.full.yaml)
BREAD_ID=1
USERS=1000
CHECK_ONLY=false
WITH_CANCEL=false
[[ "${1:-}" == "--check-only" ]] && CHECK_ONLY=true
[[ "${1:-}" == "--with-cancel" ]] && WITH_CANCEL=true

# 비밀번호는 컨테이너 안의 환경 변수로만 쓴다 (명령줄·출력에 남기지 않음)
sql() {
  "${COMPOSE[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ufivexbread -N -B fivexbread'
}
redis() {
  "${COMPOSE[@]}" exec -T redis redis-cli "$@"
}

SALE_DATE=$(echo "SELECT CURDATE() + INTERVAL 10 DAY;" | sql)
echo "판매일 $SALE_DATE"

RESULT=""
if ! $CHECK_ONLY; then
  PICKUP_SLOT_ID=$({ echo "SET @d = '$SALE_DATE';"; cat load/seed.sql; } | sql | tail -1)
  # 키 목록과 삭제를 컨테이너 안에서 한 번에 한다 (밖에서 while read 로 돌리면 docker compose exec 가 나머지 키 목록을 stdin 으로 먹는다)
  "${COMPOSE[@]}" exec -T redis sh -c 'redis-cli --scan --pattern "$1" | xargs -r redis-cli DEL > /dev/null' _ "*{$SALE_DATE}*"

  APP_IPS=$(for app in app1 app2 app3; do
    id=$("${COMPOSE[@]}" ps -q "$app")
    echo -n "$app=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$id"),"
  done)

  K6_OUT=$("${COMPOSE[@]}" run --rm --no-deps --quiet-pull \
    -e SALE_DATE="$SALE_DATE" -e PICKUP_SLOT_ID="$PICKUP_SLOT_ID" -e BREAD_ID="$BREAD_ID" -e APP_IPS="$APP_IPS" \
    -e CANCEL="$($WITH_CANCEL && echo 1 || echo 0)" \
    k6 run --quiet /load/reserve.js 2>&1 | grep -v -E "^ ?Container " || true)
  echo "$K6_OUT"
  RESULT=$(echo "$K6_OUT" | grep '^RESULT ' || true)
fi

# RESULT JSON 에서 숫자 하나 (jq 없이)
result() {
  echo "$RESULT" | grep -o "\"$1\":[0-9]*" | head -1 | cut -d: -f2
}

# 취소 복구는 커밋 후 바로 처리되고, 실패한 것은 폴러(5초 주기, 생성 후 10초)가 처리한다. 미발행 행이 0 이 될 때까지 최대 30초 기다린다
pending_outbox() {
  echo "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL;" | sql
}
if $WITH_CANCEL; then
  for _ in $(seq 1 30); do
    [[ "$(pending_outbox)" == "0" ]] && break
    sleep 1
  done
fi

read -r RESERVABLE RESERVED ITEM_SUM <<< "$(sql <<SQL
SELECT s.reservable_qty, s.reserved_qty,
       (SELECT COALESCE(SUM(i.quantity), 0) FROM reservation r JOIN reservation_item i ON i.reservation_id = r.id
        WHERE r.sale_date = s.sale_date AND i.bread_id = s.bread_id AND r.status <> 'CANCELED')
FROM daily_stock s WHERE s.sale_date = '$SALE_DATE' AND s.bread_id = $BREAD_ID;
SQL
)"
REDIS_STOCK=$(redis GET "stock:{$SALE_DATE}:$BREAD_ID")

echo
echo "== 판정 =="
FAILED=0
check() {   # check 설명 실제값 연산자 기대값
  local name=$1 actual=$2 op=$3 expected=$4
  if [[ -n "$actual" && -n "$expected" ]] && [ "$actual" "$op" "$expected" ]; then
    echo "PASS  $name ($actual $op $expected)"
  else
    echo "FAIL  $name (실제 '${actual}' / 기대 $op '${expected}')"
    FAILED=1
  fi
}

check "DB reserved_qty = 예약 수량 합" "$RESERVED" -eq "$ITEM_SUM"
check "DB reserved_qty ≤ reservable_qty (초과 예약 0)" "$RESERVED" -le "$RESERVABLE"
check "Redis 잔여 = reservable_qty − reserved_qty" "$REDIS_STOCK" -eq "$(( ${RESERVABLE:-0} - ${RESERVED:-0} ))"
if ! $CHECK_ONLY; then
  CREATED=$(result created201)
  CONFLICT=$(result conflict409)
  check "5xx 0건" "$(result serverError5xx)" -eq 0
  check "응답 ${USERS}건 모두 201 또는 409" "$(( ${CREATED:-0} + ${CONFLICT:-0} ))" -eq "$USERS"
  if $WITH_CANCEL; then
    CANCELED=$(result ok200)
    RETRY_CREATED=$(result retry201)
    CANCELED_DB=$(echo "SELECT COUNT(*) FROM reservation WHERE sale_date = '$SALE_DATE' AND status = 'CANCELED';" | sql)
    echo "취소 ${CANCELED}건, 다시 예약 성공 ${RETRY_CREATED}건, 다시 예약 409 $(result retry409)건"
    check "취소 200 = DB CANCELED" "$CANCELED" -eq "$CANCELED_DB"
    check "취소·다시 예약 응답에 예상 밖 상태 없음" "$(( $(result cancelOther) + $(result retryOther) ))" -eq 0
    check "201 − 취소 = reserved_qty" "$(( ${CREATED:-0} + ${RETRY_CREATED:-0} - ${CANCELED:-0} ))" -eq "$RESERVED"
    check "아웃박스 미발행 행 0" "$(pending_outbox)" -eq 0
  else
    check "201 개수 = reserved_qty (1건당 1개)" "$CREATED" -eq "$RESERVED"
  fi
fi

if [[ $FAILED -ne 0 ]]; then
  echo "결과: 실패"
  exit 1
fi
echo "결과: 통과"
