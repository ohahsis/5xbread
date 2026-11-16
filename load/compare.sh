#!/usr/bin/env bash
# load/compare.sh : 예약 v1(재고·한도 검사를 DB로, db-only) vs v2(Redis Lua, redis) 비교 측정 (decisions.md 6장 "예약 v1/v2 비교")
# 먼저 앱 3개와 모니터링을 띄운다: docker compose -f compose.full.yaml --profile monitoring up -d --build --wait
# 실행(레포 루트에서): ./load/compare.sh --rate-spread N --rate-hot M [--rounds 3]
#   N·M 은 시나리오마다 v1 이 무너지기 시작하는 초당 건수 (정한 방법과 값은 decisions.md 6장 "예약 v1/v2 비교")
# 필요: bash, docker, python3(결과 집계)
#
# 라운드마다 v1 → v2 순서로(3라운드면 v1 v2 v1 v2 v1 v2) 같은 시드로:
#   1) RESERVATION_MODE 를 바꿔 app1~3 을 다시 만들고 Nginx 를 다시 시작(앱 IP 가 바뀔 수 있어서)
#   2) 워밍업 1회(버림): run.sh + steady.sh spread 20초. 재시작 직후 JIT 가 덜 데워진 상태를 측정에서 뺀다
#   3) 측정: run.sh(동시 1,000건) → steady.sh spread(초당 N건 × 60초) → steady.sh hot(초당 M건 × 60초)
#   4) 측정마다 그 구간의 Prometheus 값(Hikari pending·active 최대, 서버 5xx, 서버 측 예약 p95, Redis 명령 수)을 읽는다
# 결과: load/results/{시각}/ 에 k6 summary JSON(v1-r1-burst.json 등), prometheus.tsv, summary.md. Grafana 스크린샷은 직접 캡처한다
# 판정이 하나라도 실패하면(초과 예약 등) 그 자리에서 멈춘다. 끝나면 앱은 기본 모드(redis)로 되돌린다
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f compose.full.yaml ${COMPOSE_OVERRIDE:+-f "$COMPOSE_OVERRIDE"})
RATE_SPREAD=""
RATE_HOT=""
ROUNDS=3
while [[ $# -gt 0 ]]; do
  case "$1" in
    --rate-spread) RATE_SPREAD=$2; shift 2 ;;
    --rate-hot) RATE_HOT=$2; shift 2 ;;
    --rounds) ROUNDS=$2; shift 2 ;;
    *) echo "모르는 옵션 $1" >&2; exit 2 ;;
  esac
done
[[ "$RATE_SPREAD" =~ ^[0-9]+$ && "$RATE_HOT" =~ ^[0-9]+$ ]] || { echo "--rate-spread N --rate-hot M 이 필요하다" >&2; exit 2; }
"${COMPOSE[@]}" ps --status running --services | grep -qx prometheus \
  || { echo "prometheus 가 떠 있지 않다: --profile monitoring 으로 띄운다" >&2; exit 2; }

OUT_DIR="$PWD/load/results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"
export OUT_DIR
TOKENS_DIR=$(mktemp -d)
export TOKENS_DIR
trap 'rm -rf "$TOKENS_DIR"' EXIT
echo -e "run\tmode\tround\tscenario\tstart\tend\thikari_pending_max\thikari_active_max\tserver_5xx\tserver_p95_s\tredis_commands" \
  > "$OUT_DIR/prometheus.tsv"

promql() {   # promql 식 시각(초) → 값 하나
  local q
  q=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$1")
  "${COMPOSE[@]}" exec -T prometheus wget -qO- "http://localhost:9090/api/v1/query?query=$q&time=$2" \
    | python3 -c 'import json, sys; r = json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else "0")'
}

record() {   # record 이름 모드 라운드 시나리오 시작 끝
  local name=$1 mode=$2 round=$3 scenario=$4 start=$5 end=$6
  sleep 10   # 마지막 스크레이프(5초)가 끝난 뒤에 읽는다
  local w="$(( end - start ))s"   # 시각 end 에서 거슬러 이 구간만. 앞 측정의 끝이 섞이지 않게 더 넓히지 않는다
  local post='uri="/api/v1/reservations",method="POST"'
  echo -e "$name\t$mode\t$round\t$scenario\t$start\t$end\t$(promql "max(max_over_time(hikaricp_connections_pending[$w]))" "$end")\t$(promql "max(max_over_time(hikaricp_connections_active[$w]))" "$end")\t$(promql "sum(increase(http_server_requests_seconds_count{status=~\"5..\"}[$w]))" "$end")\t$(promql "histogram_quantile(0.95, sum by (le) (increase(http_server_requests_seconds_bucket{$post}[$w])))" "$end")\t$(promql "sum(increase(lettuce_command_completion_seconds_count[$w]))" "$end")" \
    >> "$OUT_DIR/prometheus.tsv"
}

switch_mode() {
  echo "== 모드 $1 로 앱 3개 다시 만들기"
  RESERVATION_MODE=$1 "${COMPOSE[@]}" up -d --wait --force-recreate --no-deps app1 app2 app3 > /dev/null 2>&1
  "${COMPOSE[@]}" restart nginx > /dev/null 2>&1
  for app in app1 app2 app3; do
    [[ "$("${COMPOSE[@]}" exec -T "$app" printenv RESERVATION_MODE)" == "$1" ]] || { echo "$app 모드가 $1 이 아니다" >&2; exit 1; }
  done
}

measure() {   # measure 모드 라운드
  local mode=$1 round=$2 tag start rate
  tag=$([[ "$mode" == db-only ]] && echo v1 || echo v2)
  export RESERVATION_MODE=$mode
  # 토큰이 10분 넘었으면 여기서 다시 로그인한다. 모드 하나의 측정(약 7분) 안에서 20분 기준을 넘겨 측정 구간에 로그인이 섞이지 않게
  TOKENS_MAX_AGE_MIN=10 ./load/steady.sh --login-only > /dev/null
  echo "== $tag r$round 워밍업 (버림)"
  NAME="$tag-r$round-warmup" ./load/run.sh > "$OUT_DIR/$tag-r$round-warmup.log" 2>&1
  NAME="$tag-r$round-warmup-steady" ./load/steady.sh --scenario spread --rate "$RATE_SPREAD" --duration 20s \
    >> "$OUT_DIR/$tag-r$round-warmup.log" 2>&1
  for scenario in burst spread hot; do
    echo "== $tag r$round $scenario"
    start=$(date +%s)
    if [[ $scenario == burst ]]; then
      NAME="$tag-r$round-burst" ./load/run.sh > "$OUT_DIR/$tag-r$round-burst.log" 2>&1 \
        || { tail -15 "$OUT_DIR/$tag-r$round-burst.log"; exit 1; }
    else
      rate=$([[ $scenario == spread ]] && echo "$RATE_SPREAD" || echo "$RATE_HOT")
      NAME="$tag-r$round-$scenario" ./load/steady.sh --scenario "$scenario" --rate "$rate" \
        > "$OUT_DIR/$tag-r$round-$scenario.log" 2>&1 || { tail -15 "$OUT_DIR/$tag-r$round-$scenario.log"; exit 1; }
    fi
    grep -E '^RESULT|^(PASS|FAIL)' "$OUT_DIR/$tag-r$round-$scenario.log" | cut -c1-220
    record "$tag-r$round-$scenario" "$tag" "$round" "$scenario" "$start" "$(date +%s)"
  done
}

{
  echo "측정 시작 $(date '+%F %T') spread=${RATE_SPREAD}/s hot=${RATE_HOT}/s rounds=$ROUNDS commit=$(git rev-parse --short HEAD)"
  docker info --format 'Docker CPUs={{.NCPU}} Mem={{.MemTotal}} Server={{.ServerVersion}}'
} | tee "$OUT_DIR/env.txt"
for round in $(seq 1 "$ROUNDS"); do
  for mode in db-only redis; do
    switch_mode "$mode"
    measure "$mode" "$round"
  done
done
switch_mode redis
unset RESERVATION_MODE

python3 - "$OUT_DIR" "spread ${RATE_SPREAD}/s, hot ${RATE_HOT}/s" <<'PY'
import json, statistics, sys, pathlib, csv
out, rate = pathlib.Path(sys.argv[1]), sys.argv[2]
prom = {r["run"]: r for r in csv.DictReader(open(out / "prometheus.tsv"), delimiter="\t")}
rows = []
for name, p in prom.items():
    s = json.load(open(out / f"{name}.json"))
    m = s["metrics"]
    d = m.get("http_req_duration{name:reserve}", {}).get("values", {})
    cnt = lambda k: m.get(k, {}).get("values", {}).get("count", 0)
    if p["scenario"] == "burst":
        thr = 1000 / (d.get("max", 0) / 1000) if d.get("max") else 0      # 1,000건을 모두 받은 시간 기준
    else:
        thr = (cnt("reserve_201") + cnt("reserve_409")) / 60   # 정상 처리(201·409) 응답 기준. hot 은 500건 뒤로 매진 409
    rows.append(dict(run=name, mode=p["mode"], scenario=p["scenario"], med=d.get("med", 0), p95=d.get("p(95)", 0),
                     thr=thr, s5xx=cnt("reserve_5xx"), dropped=cnt("dropped_iterations"),
                     pending=float(p["hikari_pending_max"]), active=float(p["hikari_active_max"]),
                     srv5xx=float(p["server_5xx"]), redis=float(p["redis_commands"])))
lines = [f"# 예약 v1/v2 비교 ({rate})", "", "## 측정별", "",
         "| 측정 | 중앙값 ms | p95 ms | 처리량 /s | 5xx | dropped | Hikari pending 최대 | active 최대 | Redis 명령 |",
         "|---|---|---|---|---|---|---|---|---|"]
for r in rows:
    lines.append(f"| {r['run']} | {r['med']:.0f} | {r['p95']:.0f} | {r['thr']:.1f} | {r['s5xx']} | {r['dropped']} | "
                 f"{r['pending']:.0f} | {r['active']:.0f} | {r['redis']:.0f} |")
lines += ["", "## 라운드 중앙값 (최소~최대)", "",
          "| 시나리오 | 모드 | 중앙값 ms | p95 ms | 처리량 /s | 5xx 합 | dropped 합 | Hikari pending 최대 |", "|---|---|---|---|---|---|---|---|"]
def rng(v): return f"{statistics.median(v):.0f} ({min(v):.0f}~{max(v):.0f})"
for sc in ["burst", "spread", "hot"]:
    for mode in ["v1", "v2"]:
        g = [r for r in rows if r["scenario"] == sc and r["mode"] == mode]
        if not g: continue
        lines.append(f"| {sc} | {mode} | {rng([r['med'] for r in g])} | {rng([r['p95'] for r in g])} | "
                     f"{rng([r['thr'] for r in g])} | {sum(r['s5xx'] for r in g)} | {sum(r['dropped'] for r in g)} | "
                     f"{max(r['pending'] for r in g):.0f} |")
(out / "summary.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
PY
echo "결과: $OUT_DIR"
