#!/usr/bin/env bash
# load/realtime-check.sh : 앱 3개 + Nginx 에서 잔여 실시간 갱신(C-03)이 다른 앱에 붙은 손님에게도 가는지 판정한다
# 먼저 앱 3개를 띄운다: docker compose -f compose.full.yaml up -d --build --wait
# 실행(레포 루트에서): ./load/realtime-check.sh   → 하나라도 틀리면 exit 1
#
# 1) run.sh 와 같은 테스트 판매일(10일 뒤)·손님을 seed.sql 로 다시 넣고 그 날의 Redis 키를 지운다
# 2) k6 컨테이너로 realtime-check.js: STOMP 리스너 6개(앱 3개에 나뉘어 붙음) + 예약 3건
# 3) 판정: 리스너 모두 101·ERROR 없음, 받은 버전이 증가 순서이고 마지막 버전 = Redis stockver,
#    마지막 잔여 = Redis 잔여 = 목록 잔여, 리스너가 붙은 앱과 다른 앱이 처리한 예약의 메시지까지 모두 받은 리스너가 1개 이상
#    (앱 간 전파를 실제로 거침. 예약은 1초 간격이라 묶이지 않고 건마다 버전 1개씩 온다)
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f compose.full.yaml)
BREAD_ID=1

sql() {
  "${COMPOSE[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ufivexbread -N -B fivexbread'
}
redis() {
  "${COMPOSE[@]}" exec -T redis redis-cli "$@"
}

SALE_DATE=$(echo "SELECT CURDATE() + INTERVAL 10 DAY;" | sql)
PICKUP_SLOT_ID=$({ echo "SET @d = '$SALE_DATE';"; cat load/seed.sql; } | sql | tail -1)
"${COMPOSE[@]}" exec -T redis sh -c 'redis-cli --scan --pattern "$1" | xargs -r redis-cli DEL > /dev/null' _ "*{$SALE_DATE}*"
echo "판매일 $SALE_DATE"

K6_OUT=$("${COMPOSE[@]}" run --rm --no-deps --quiet-pull \
  -e SALE_DATE="$SALE_DATE" -e PICKUP_SLOT_ID="$PICKUP_SLOT_ID" -e BREAD_ID="$BREAD_ID" \
  k6 run --quiet /load/realtime-check.js 2>&1 | grep -o 'RESULT .*}' | sed 's/\\"/"/g' || true)
echo "$K6_OUT"

REDIS_REMAINING=$(redis GET "stock:{$SALE_DATE}:$BREAD_ID" | tr -d '\r')
REDIS_VERSION=$(redis GET "stockver:{$SALE_DATE}" | tr -d '\r')
echo "Redis 잔여 $REDIS_REMAINING, 버전 $REDIS_VERSION"

# 판정은 python 없이 쓸 수 있게 node 대신 awk·grep 으로 한다
FAIL=0
fail() { echo "실패: $1"; FAIL=1; }
WRITE=$(echo "$K6_OUT" | grep '^RESULT write' || true)
[[ -z "$WRITE" ]] && fail "예약 결과 없음"
LIST_REMAINING=$(echo "$WRITE" | grep -o '"listRemaining":[0-9]*' | cut -d: -f2)
LIST_VERSION=$(echo "$WRITE" | grep -o '"listVersion":[0-9]*' | cut -d: -f2)
[[ "$LIST_REMAINING" == "$REDIS_REMAINING" ]] || fail "목록 잔여 $LIST_REMAINING ≠ Redis $REDIS_REMAINING"
[[ "$LIST_VERSION" == "$REDIS_VERSION" ]] || fail "목록 버전 $LIST_VERSION ≠ Redis $REDIS_VERSION"
WRITERS=$(echo "$WRITE" | grep -o '201@[0-9.:]*' | cut -d@ -f2 | cut -d: -f1 | sort -u)

CROSS=0
while read -r line; do
  [[ -z "$line" ]] && continue
  upstream=$(echo "$line" | grep -o '"upstream":"[0-9.]*' | cut -d'"' -f4)
  status=$(echo "$line" | grep -o '"status":[0-9]*' | cut -d: -f2)
  errors=$(echo "$line" | grep -o '"errors":[0-9]*' | cut -d: -f2)
  versions=$(echo "$line" | grep -o '"versions":\[[0-9,]*\]' | grep -o '[0-9][0-9,]*' || true)
  remaining=$(echo "$line" | grep -o '"remaining":[0-9]*' | cut -d: -f2 || true)
  last_version=${versions##*,}
  [[ "$status" == "101" ]] || fail "리스너 $upstream 상태 $status"
  [[ "$errors" == "0" ]] || fail "리스너 $upstream ERROR 프레임 $errors"
  [[ "$(echo "$versions" | tr ',' '\n')" == "$(echo "$versions" | tr ',' '\n' | sort -n)" ]] || fail "리스너 $upstream 버전 순서 $versions"
  [[ "$last_version" == "$REDIS_VERSION" ]] || fail "리스너 $upstream 마지막 버전 $last_version ≠ Redis $REDIS_VERSION"
  [[ "$remaining" == "$REDIS_REMAINING" ]] || fail "리스너 $upstream 마지막 잔여 $remaining ≠ Redis $REDIS_REMAINING"
  # 이 리스너의 앱이 아닌 앱이 처리한 예약이 있고, 그 버전까지 다 받았으면(위 판정) 앱 간 전파를 거친 것이다
  if echo "$WRITERS" | grep -vqx "$upstream"; then CROSS=$((CROSS + 1)); fi
done < <(echo "$K6_OUT" | grep '^RESULT listen' || true)
[[ $(echo "$K6_OUT" | grep -c '^RESULT listen') -eq 6 ]] || fail "리스너 결과가 6개가 아님"
[[ $CROSS -ge 1 ]] || fail "다른 앱이 처리한 예약을 받은 리스너가 없음 (앱 간 전파를 확인하지 못함)"
echo "예약 처리 앱: $(echo $WRITERS) / 다른 앱이 처리한 예약까지 받은 리스너: $CROSS"

if [[ $FAIL -eq 0 ]]; then echo "판정: 통과"; else echo "판정: 실패"; exit 1; fi
