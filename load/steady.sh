#!/usr/bin/env bash
# load/steady.sh : 앱 3개 + Nginx 에 초당 N건 예약을 일정 시간 보내고 정합성을 판정한다 (decisions.md 6장 "예약 v1/v2 비교")
# 먼저 앱 3개를 띄운다: docker compose -f compose.full.yaml up -d --build --wait
# 실행(레포 루트에서): ./load/steady.sh --scenario spread|hot --rate N [--duration 60s]
#                     ./load/steady.sh --login-only   → 시드·로그인만 (compare.sh 가 측정 구간 밖에서 토큰을 준비할 때)
#   spread: 판매일 5개 × 빵 20종에 나눔 / hot: 한 판매일의 빵 1 한 행에 몰림, 500건 뒤로는 매진 409 (steady.js)
#
# 1) seed-steady.sql 로 손님 10,000명·판매일 5개(20일 뒤부터)·재고를 다시 넣고 그 날들의 Redis 재고 키를 지운다
# 2) 토큰이 없거나 20분 넘었으면 login.js 로 다시 로그인 (TOKENS_DIR 을 주면 그 폴더를 재사용, 아니면 임시 폴더를 만들고 끝나면 지움)
# 3) steady.js. OUT_DIR·NAME 을 주면 k6 summary JSON 을 OUT_DIR/NAME.json 으로 남긴다
# 4) 판정(하나라도 틀리면 exit 1): 판매일·빵마다 reserved_qty = 예약 수량 합 ≤ reservable_qty,
#    RESERVATION_MODE 가 db-only 가 아니면 hot 행의 Redis 잔여 = DB 잔여. 5xx·dropped 는 결과로만 보여 준다(무너지는 지점을 찾는 값)
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f compose.full.yaml ${COMPOSE_OVERRIDE:+-f "$COMPOSE_OVERRIDE"})
SCENARIO=spread
RATE=""
DURATION=60s
USERS=10000
LOGIN_ONLY=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) SCENARIO=$2; shift 2 ;;
    --rate) RATE=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    --login-only) LOGIN_ONLY=true; RATE=0; shift ;;
    *) echo "모르는 옵션 $1" >&2; exit 2 ;;
  esac
done
[[ "$SCENARIO" == spread || "$SCENARIO" == hot ]] || { echo "--scenario 는 spread 또는 hot" >&2; exit 2; }
[[ "$RATE" =~ ^[0-9]+$ ]] || { echo "--rate N 이 필요하다" >&2; exit 2; }
# 손님마다 하루 3건까지라 요청 수가 USERS × 3 을 넘으면 422 가 섞여 측정이 달라진다
if (( RATE * ${DURATION%s} > USERS * 3 )); then
  echo "초당 $RATE 건 × $DURATION 은 손님 $USERS 명 × 하루 3건을 넘는다. 비율이나 시간을 줄인다" >&2
  exit 2
fi

sql() {
  "${COMPOSE[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ufivexbread -N -B fivexbread'
}
redis() {
  "${COMPOSE[@]}" exec -T redis redis-cli "$@"
}

FIRST_DATE=$(echo "SELECT CURDATE() + INTERVAL 20 DAY;" | sql)
TARGETS=$({ echo "SET @d = '$FIRST_DATE';"; cat load/seed-steady.sql; } | sql | tail -5 | awk '{printf "%s%s:%s", (NR>1?",":""), $1, $2}')
for k in 0 1 2 3 4; do
  d=$(echo "SELECT '$FIRST_DATE' + INTERVAL $k DAY;" | sql)
  "${COMPOSE[@]}" exec -T redis sh -c 'redis-cli --scan --pattern "$1" | xargs -r redis-cli DEL > /dev/null' _ "*{$d}*"
done

if [[ -z "${TOKENS_DIR:-}" ]]; then
  TOKENS_DIR=$(mktemp -d)
  trap 'rm -rf "$TOKENS_DIR"' EXIT
fi
if [[ -z "$(find "$TOKENS_DIR" -name tokens.json -mmin "-${TOKENS_MAX_AGE_MIN:-20}" 2>/dev/null)" ]]; then   # Access Token 30분보다 짧게
  echo "로그인 손님 ${USERS}명 (토큰은 $TOKENS_DIR 에만 둔다)"
  "${COMPOSE[@]}" run --rm --no-deps --quiet-pull -v "$TOKENS_DIR:/work" -e USERS="$USERS" \
    k6 run --quiet /load/login.js 2>&1 | grep '^LOGIN ' || { echo "로그인 실패" >&2; exit 1; }
fi

$LOGIN_ONLY && exit 0

OUT_ARGS=()
if [[ -n "${OUT_DIR:-}" ]]; then
  OUT_ARGS=(-v "$OUT_DIR:/out" -e OUT_FILE="/out/${NAME:-steady-$SCENARIO}.json")
fi
echo "일정 부하 $SCENARIO 초당 $RATE 건 $DURATION (판매일 $FIRST_DATE 부터 5일)"
K6_OUT=$("${COMPOSE[@]}" run --rm --no-deps --quiet-pull -v "$TOKENS_DIR:/work:ro" ${OUT_ARGS[@]+"${OUT_ARGS[@]}"} \
  -e SCENARIO="$SCENARIO" -e RATE="$RATE" -e DURATION="$DURATION" -e USERS="$USERS" -e TARGETS="$TARGETS" \
  k6 run --quiet /load/steady.js 2>&1 | grep -v -E "^ ?Container " || true)
echo "$K6_OUT" | grep '^RESULT ' || { echo "$K6_OUT" | tail -20; echo "k6 결과 없음" >&2; exit 1; }

echo
echo "== 판정 =="
BAD=$(sql <<SQL
SELECT COUNT(*) FROM daily_stock s
WHERE s.sale_date BETWEEN '$FIRST_DATE' AND '$FIRST_DATE' + INTERVAL 4 DAY
  AND (s.reserved_qty > s.reservable_qty
       OR s.reserved_qty <> (SELECT COALESCE(SUM(i.quantity), 0) FROM reservation r
                             JOIN reservation_item i ON i.reservation_id = r.id
                             WHERE r.sale_date = s.sale_date AND i.bread_id = s.bread_id AND r.status <> 'CANCELED'));
SQL
)
FAILED=0
if [[ "$BAD" == 0 ]]; then
  echo "PASS  판매일·빵 100행 모두 reserved_qty = 예약 수량 합 ≤ reservable_qty"
else
  echo "FAIL  어긋난 행 $BAD 개"
  FAILED=1
fi
if [[ "${RESERVATION_MODE:-redis}" != "db-only" ]]; then
  read -r REMAINING <<< "$(echo "SELECT reservable_qty - reserved_qty FROM daily_stock WHERE sale_date = '$FIRST_DATE' AND bread_id = 1;" | sql)"
  REDIS_STOCK=$(redis GET "stock:{$FIRST_DATE}:1")
  if [[ "$REDIS_STOCK" == "$REMAINING" ]]; then
    echo "PASS  빵 1 Redis 잔여 = DB 잔여 ($REDIS_STOCK)"
  else
    echo "FAIL  빵 1 Redis 잔여 '$REDIS_STOCK' ≠ DB 잔여 $REMAINING"
    FAILED=1
  fi
fi
[[ $FAILED -eq 0 ]] && echo "결과: 통과" || { echo "결과: 실패"; exit 1; }
