#!/usr/bin/env bash
# load/chaos.sh : 장애 실험 (decisions.md 6장 "장애 실험"). 일정 부하를 거는 중에 Redis·앱·MySQL 을 하나씩 멈추고
# 응답 코드 분포·회복 시간·끝난 뒤 정합성을 잰다.
# 먼저 앱 3개와 모니터링을 띄운다: docker compose -f compose.full.yaml --profile monitoring up -d --build --wait
# 실행(레포 루트에서): ./load/chaos.sh [--rate 20] [--duration 180] [--only redis-pause|app2-kill|mysql-restart]
#                     ./load/chaos.sh --report load/results/chaos-{시각}   → 이미 남은 결과로 summary.md 만 다시 만든다
# 필요: bash, docker, python3
#
# 실험마다 steady.sh spread(초당 RATE 건, CANCEL_RATIO 만큼 바로 취소)를 백그라운드로 돌리고, 부하 시작 BASELINE 초 뒤에:
#   redis-pause  : docker pause redis → 30초 → unpause
#   app2-kill    : docker kill app2 → 20초 → start (healthy 까지 기다림)
#   mysql-restart: docker restart mysql (healthy 까지 기다림)
# 취소를 섞는 이유: 취소의 재고 복구는 아웃박스(커밋 후 바로 처리 + 5초 폴러)를 거치므로, 장애 중 남은 이벤트가 끝나고 발행되는지 본다.
# 취소는 VU 가 앞서 만든 예약에서 꺼내므로(steady.js) Redis 장애로 예약이 503 인 동안에도 들어간다.
#
# 측정(요청마다의 k6 CSV 에서, 초 단위):
#   응답 코드 분포  : 예약·취소의 201/200·409·422·503·502·기타 5xx·연결 실패(k6 status 0)
#   오류 구간       : 첫 5xx(또는 연결 실패) ~ 마지막 5xx
#   회복(응답)      : 장애를 거둔 시각(unpause / start 명령 / restart 명령) → 마지막 5xx 까지. 그 뒤로 오류가 없으면 0
#   app2 복귀       : app2-kill 만. start 명령 → app2 가 처음 다시 응답한 시각 (X-Upstream 의 마지막 주소 = 응답한 앱)
#   다른 앱으로 넘김: X-Upstream 에 주소가 둘 이상인 응답(Nginx 가 연결 실패로 다음 앱에 다시 보냄)
#   아웃박스        : 이 실험 중 생긴 행 수, 한 번이라도 실패한 행, 최대 attempts, 생성 → 발행 최대 초
# 판정(부하가 끝난 뒤, 이 실험의 5일치 100행):
#   초과 예약 0     : reserved_qty = 예약 수량 합 ≤ reservable_qty (steady.sh 와 같은 SQL)
#   Redis = DB      : 키가 있는 행마다 Redis 잔여 = reservable_qty − reserved_qty. 키가 없으면 다음 예약 때 DB 에서 다시 싣는다
#   아웃박스 미발행 0: published_at IS NULL 인 행 (dead 는 따로 센다)
#   부하가 끝난 뒤 SETTLE 초 안에 셋이 맞으면 "정합성 회복" 시간을 남긴다. 그때까지 Redis ≠ DB 면 N-05 정기 점검(5분 간격 2회 관측)이
#   맞추는지 RECONCILE_WAIT 초 더 본다.
# 결과: load/results/chaos-{시각}/ 에 실험마다 k6 CSV·summary JSON·로그·이벤트 시각, 그리고 summary.md
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f compose.full.yaml ${COMPOSE_OVERRIDE:+-f "$COMPOSE_OVERRIDE"})
RATE=20
DURATION=180
BASELINE=30
CANCEL_RATIO=0.2
SETTLE=120
RECONCILE_WAIT=660
EXPERIMENTS=(redis-pause app2-kill mysql-restart)
REPORT_ONLY=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --rate) RATE=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    --only) EXPERIMENTS=("$2"); shift 2 ;;
    --report) REPORT_ONLY=$2; shift 2 ;;
    *) echo "모르는 옵션 $1" >&2; exit 2 ;;
  esac
done
report() {   # report 결과폴더 실험... → 폴더에 summary.md·{실험}.timeline.tsv·{실험}.result.json
python3 - "$@" <<'PY'
import csv, collections, json, pathlib, sys
out = pathlib.Path(sys.argv[1])
lines = ["| 실험 | 요청(예약/취소) | 2xx | 409·422 | 503 | 502 | 기타 5xx | 연결 실패 | 다른 앱으로 넘김 | 오류 구간 | 회복(응답) | app2 복귀 | 정합성 회복 | 아웃박스(행/실패 행/최대 시도/최대 지연) | 초과 예약 행 | Redis≠DB 행 | 미발행 | dead |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
for exp in sys.argv[2:]:
    ev = dict(l.rstrip("\n").split("\t", 1) for l in open(out / f"{exp}.events") if "\t" in l)
    ips = {ev[k].strip(): k[3:] for k in ev if k.startswith("ip_")}
    if ev.get("app2_ip_after"):
        ips[ev["app2_ip_after"].strip()] = "app2"
    fault_start, fault_end = int(ev["fault_start"]), int(ev["fault_end"])
    by_name = collections.Counter()
    status = collections.Counter()
    per_sec = collections.defaultdict(collections.Counter)
    errors = []
    app2_back = None
    rerouted = 0
    with open(out / f"{exp}.csv") as f:
        for r in csv.DictReader(f):
            ts = int(float(r["timestamp"]))
            if r["metric_name"] == "http_reqs":
                s = int(r["status"] or 0)
                by_name[r["name"]] += 1
                status[s] += 1
                per_sec[ts][s] += 1
                if s == 0 or s >= 500:
                    errors.append(ts)
            elif r["metric_name"] == "upstream":
                tags = dict(kv.split("=", 1) for kv in r["extra_tags"].split("&") if "=" in kv)
                addrs = [a.strip() for a in tags.get("upstream", "").split(",") if a.strip()]
                if len(addrs) > 1:                                    # 연결 실패로 다음 앱에 다시 보냄
                    rerouted += 1
                served = ips.get(addrs[-1].split(":")[0]) if addrs else None   # 마지막 주소가 응답한 앱
                if served == "app2" and ts >= fault_end and app2_back is None:
                    app2_back = ts - fault_end
    with open(out / f"{exp}.timeline.tsv", "w") as f:            # 초별 응답 코드 (장애 시작 기준 초)
        codes = sorted({c for s in per_sec.values() for c in s})
        f.write("t\t" + "\t".join(map(str, codes)) + "\n")
        for ts in sorted(per_sec):
            f.write(f"{ts - fault_start}\t" + "\t".join(str(per_sec[ts][c]) for c in codes) + "\n")
    ok = sum(v for k, v in status.items() if 200 <= k < 300)
    other5xx = sum(v for k, v in status.items() if k >= 500 and k not in (502, 503))
    window = f"{errors[0] - fault_start:+d}~{max(errors) - fault_start:+d}초" if errors else "없음"
    recovery = f"{max(0, max(errors) - fault_end)}초" if errors else "0초"
    pending, dead, over, mismatch = ev["check"].split()
    settle = f"{ev['settle_s']}초" if ev.get("settle_s") else (f"N-05 뒤 {ev['reconcile_s']}초" if ev.get("reconcile_s") else "맞지 않음")
    rows_, failed_, max_attempts_, max_delay_ = ev.get("outbox", "0 0 0 0").split()
    lines.append(f"| {exp} | {by_name['reserve']}/{by_name['cancel']} | {ok} | {status[409] + status[422]} | {status[503]} | {status[502]} "
                 f"| {other5xx} | {status[0]} | {rerouted} | {window} | {recovery} | {'' if exp != 'app2-kill' else (str(app2_back) + '초' if app2_back is not None else '복귀 안 함')} "
                 f"| {settle} | {rows_}/{failed_}/{max_attempts_}/{max_delay_}초 | {over} | {mismatch} | {pending} | {dead} |")
    json.dump({"status": {str(k): v for k, v in sorted(status.items())}, "by_name": by_name, "errors": len(errors),
               "app2_back_s": app2_back, "rerouted": rerouted, "events": ev}, open(out / f"{exp}.result.json", "w"), ensure_ascii=False, indent=1)
note = ("\n오류 구간·회복은 장애 시작(pause/kill/restart 명령) 기준 초. 회복(응답) = 장애를 거둔 시각(unpause/start/restart) → 마지막 5xx·연결 실패.\n"
        "정합성 회복 = 부하가 끝난 뒤 미발행 0·Redis = DB 가 될 때까지. 초별 응답 코드는 {실험}.timeline.tsv\n")
(out / "summary.md").write_text((out / "env.txt").read_text() + "\n" + "\n".join(lines) + "\n" + note)
print("\n".join(lines))
PY
}

if [[ -n "$REPORT_ONLY" ]]; then
  exps=()
  for e in "${EXPERIMENTS[@]}"; do [[ -f "$REPORT_ONLY/$e.events" ]] && exps+=("$e"); done
  report "$REPORT_ONLY" "${exps[@]}"
  exit 0
fi

"${COMPOSE[@]}" ps --status running --services | grep -qx prometheus \
  || { echo "prometheus 가 떠 있지 않다: --profile monitoring 으로 띄운다" >&2; exit 2; }

OUT_DIR="$PWD/load/results/chaos-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"
TOKENS_DIR=$(mktemp -d)
trap 'rm -rf "$TOKENS_DIR"; "${COMPOSE[@]}" unpause redis > /dev/null 2>&1 || true' EXIT

sql() {
  "${COMPOSE[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ufivexbread -N -B fivexbread'
}
container() { "${COMPOSE[@]}" ps -aq "$1"; }
health() { docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$(container "$1")"; }
ip() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(container "$1")"; }
wait_healthy() {   # wait_healthy 서비스 최대초
  local i
  for ((i = 0; i < $2; i++)); do
    [[ "$(health "$1")" == healthy ]] && return 0
    sleep 1
  done
  echo "$1 이 $2 초 안에 healthy 가 되지 않음" >&2
  return 1
}
mark() { echo -e "$1\t$(date +%s)" >> "$EVENTS"; }
max_outbox_id() { echo "SELECT COALESCE(MAX(id), 0) FROM outbox_event;" | sql; }
outbox_stats() {   # outbox_stats 시작id → "행 실패행 최대attempts 최대지연초"
  echo "SELECT COUNT(*), COALESCE(SUM(attempts > 0), 0), COALESCE(MAX(attempts), 0), COALESCE(MAX(TIMESTAMPDIFF(SECOND, created_at, published_at)), 0) FROM outbox_event WHERE id > $1;" | sql
}

first_date() { echo "SELECT CURDATE() + INTERVAL 20 DAY;" | sql; }

# 미발행(dead 제외) dead 초과예약행 Redis≠DB행 을 한 줄로
check() {
  local d=$1 pending dead over rows vals mismatch
  read -r pending dead <<< "$(echo "SELECT COALESCE(SUM(dead_at IS NULL), 0), COALESCE(SUM(dead_at IS NOT NULL), 0) FROM outbox_event WHERE published_at IS NULL;" | sql)"
  over=$(sql <<SQL
SELECT COUNT(*) FROM daily_stock s
WHERE s.sale_date BETWEEN '$d' AND '$d' + INTERVAL 4 DAY
  AND (s.reserved_qty > s.reservable_qty
       OR s.reserved_qty <> (SELECT COALESCE(SUM(i.quantity), 0) FROM reservation r
                             JOIN reservation_item i ON i.reservation_id = r.id
                             WHERE r.sale_date = s.sale_date AND i.bread_id = s.bread_id AND r.status <> 'CANCELED'));
SQL
)
  rows=$(echo "SELECT sale_date, bread_id, reservable_qty - reserved_qty FROM daily_stock WHERE sale_date BETWEEN '$d' AND '$d' + INTERVAL 4 DAY ORDER BY sale_date, bread_id;" | sql)
  # shellcheck disable=SC2046
  vals=$("${COMPOSE[@]}" exec -T redis redis-cli MGET $(echo "$rows" | awk '{printf "stock:{%s}:%s ", $1, $2}'))
  mismatch=$(paste <(echo "$rows") <(echo "$vals") | awk -F'\t' '$4 != "" && $4 != $3 {n++} END {print n + 0}')
  echo "$pending $dead $over $mismatch"
}

inject() {
  case "$1" in
    redis-pause)
      mark fault_start; "${COMPOSE[@]}" pause redis > /dev/null 2>&1
      sleep 30
      "${COMPOSE[@]}" unpause redis > /dev/null 2>&1; mark fault_end ;;
    app2-kill)
      echo -e "app2_ip_before\t$(ip app2)" >> "$EVENTS"
      mark fault_start; "${COMPOSE[@]}" kill app2 > /dev/null 2>&1
      sleep 20
      mark fault_end; "${COMPOSE[@]}" start app2 > /dev/null 2>&1
      wait_healthy app2 180; mark healthy
      echo -e "app2_ip_after\t$(ip app2)" >> "$EVENTS" ;;
    mysql-restart)
      mark fault_start; "${COMPOSE[@]}" restart mysql > /dev/null 2>&1; mark fault_end
      wait_healthy mysql 180; mark healthy ;;
    *) echo "모르는 실험 $1" >&2; exit 2 ;;
  esac
}

run_one() {
  local exp=$1 d log pid i result settle_s="" reconcile_s="" outbox_from
  EVENTS="$OUT_DIR/$exp.events"
  log="$OUT_DIR/$exp.log"
  : > "$EVENTS"
  echo "== $exp"
  "${COMPOSE[@]}" up -d --wait mysql redis app1 app2 app3 > /dev/null 2>&1
  "${COMPOSE[@]}" restart nginx > /dev/null 2>&1      # 앞 실험의 max_fails 상태·앱 IP 를 비운다
  for app in app1 app2 app3; do echo -e "ip_$app\t$(ip $app)" >> "$EVENTS"; done
  outbox_from=$(max_outbox_id)

  OUT_DIR="$OUT_DIR" NAME="$exp" K6_CSV=1 CANCEL_RATIO="$CANCEL_RATIO" TRACK_UPSTREAM=1 TOKENS_DIR="$TOKENS_DIR" \
    ./load/steady.sh --scenario spread --rate "$RATE" --duration "${DURATION}s" > "$log" 2>&1 &
  pid=$!
  for ((i = 0; i < 120; i++)); do grep -q '^일정 부하' "$log" && break; sleep 1; done
  sleep 3; mark load_start                             # k6 컨테이너가 뜨는 데 걸리는 시간
  sleep "$BASELINE"
  inject "$exp"
  wait "$pid" || echo "  (steady.sh 판정 실패. 부하 직후의 판정이라 아래 정착 판정을 본다)"
  mark load_end
  d=$(first_date)

  for ((i = 0; i < SETTLE; i += 2)); do
    result=$(check "$d")
    read -r p _ _ m <<< "$result"
    if [[ $p == 0 && $m == 0 ]]; then settle_s=$i; break; fi
    sleep 2
  done
  if [[ -z "$settle_s" ]]; then
    echo "  $SETTLE 초 안에 맞지 않음 (미발행 dead 초과 Redis≠DB = $result). N-05 를 최대 $RECONCILE_WAIT 초 기다림"
    for ((i = 0; i < RECONCILE_WAIT; i += 10)); do
      result=$(check "$d")
      read -r p _ _ m <<< "$result"
      if [[ $p == 0 && $m == 0 ]]; then reconcile_s=$((SETTLE + i)); break; fi
      sleep 10
    done
  fi
  result=$(check "$d")
  echo -e "settle_s\t${settle_s}\nreconcile_s\t${reconcile_s}\ncheck\t$result\noutbox\t$(outbox_stats "$outbox_from" | tr '\t' ' ')" >> "$EVENTS"
  echo "  미발행 dead 초과예약행 Redis≠DB행 = $result (정착 ${settle_s:-–}초${reconcile_s:+, N-05 뒤 ${reconcile_s}초})"
}

echo "로그인 (토큰은 $TOKENS_DIR 에만 둔다)"
TOKENS_DIR="$TOKENS_DIR" ./load/steady.sh --login-only > /dev/null
{
  echo "실험 시작 $(date '+%F %T') rate=${RATE}/s duration=${DURATION}s baseline=${BASELINE}s cancel=${CANCEL_RATIO} commit=$(git rev-parse --short HEAD)"
  docker info --format 'Docker CPUs={{.NCPU}} Mem={{.MemTotal}} Server={{.ServerVersion}}'
} | tee "$OUT_DIR/env.txt"
for exp in "${EXPERIMENTS[@]}"; do
  run_one "$exp"
done

report "$OUT_DIR" "${EXPERIMENTS[@]}"
echo "결과: $OUT_DIR"
