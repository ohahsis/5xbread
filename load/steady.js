// load/steady.js : 일정 부하 예약 (decisions.md 6장 "예약 v1/v2 비교"). 실행은 steady.sh
//
// 초당 RATE 건을 DURATION 동안 보낸다(constant-arrival-rate). 응답이 늦어 VU(최대 MAX_VUS)가 모자라면 k6 가 요청을
// 보내지 못하고 dropped_iterations 로 센다. 이 값과 응답 시간이 "무너지기 시작하는 지점"의 기준이다.
// SCENARIO
//   spread: 판매일 5개 × 빵 20종에 나눠 보낸다. 손님 i 는 늘 같은 판매일(i % 5)에만 보내 하루 3건을 넘지 않는다
//   hot   : 모든 요청이 첫 판매일의 빵 1 한 행에 몰린다(인기 빵). 총량 500(스키마 상한)이라 500건 뒤로는 매진 409
// 손님은 요청 번호 % USERS 로 돌린다. 요청 수가 USERS × 3 을 넘으면 하루 3건(422)에 걸리므로 steady.sh 가 미리 막는다.
// CANCEL_RATIO(기본 0): 반복마다 이 확률로, 이 VU 가 앞서 201 을 받은 예약 중 가장 오래된 것을 취소한다.
//   취소의 재고 복구가 아웃박스를 거치게 하려는 것(chaos.sh). 방금 만든 예약이 아니라 쌓아 둔 예약을 취소하므로
//   Redis 장애로 예약이 모두 503 인 동안에도 취소(DB 만으로 성공, 아웃박스 행이 쌓임)는 계속 들어간다.
//   취소한 예약은 하루 건수에서 빠지므로 422 계산은 그대로다
// TRACK_UPSTREAM=1: 응답마다 upstream 카운터에 처리한 앱 주소(X-Upstream, Nginx 가 다시 보냈으면 "주소1, 주소2")를 태그로 남긴다.
//   chaos.sh 가 k6 CSV 출력에서 앱이 언제 다시 요청을 받는지 읽는다(IP → 앱 이름은 chaos.sh 가 붙인다)
// 환경 변수: BASE_URL, SCENARIO, RATE, DURATION, USERS, MAX_VUS, TARGETS("날짜:시간대id,..." 5개), OUT_FILE, CANCEL_RATIO, TRACK_UPSTREAM
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://nginx:8081';
const SCENARIO = __ENV.SCENARIO || 'spread';
const RATE = Number(__ENV.RATE);
const DURATION = __ENV.DURATION || '60s';
const USERS = Number(__ENV.USERS || 10000);
const MAX_VUS = Number(__ENV.MAX_VUS || 1000);
const TARGETS = (__ENV.TARGETS || '').split(',').filter(Boolean).map((pair) => {
  const [date, slot] = pair.split(':');
  return { date, slot: Number(slot) };
});
const BREADS = 20;
const CANCEL_RATIO = Number(__ENV.CANCEL_RATIO || 0);
const TRACK_UPSTREAM = __ENV.TRACK_UPSTREAM === '1';

const tokens = new SharedArray('tokens', () => JSON.parse(open('/work/tokens.json')));

const counters = {
  201: new Counter('reserve_201'),
  409: new Counter('reserve_409'),
  422: new Counter('reserve_422'),
  429: new Counter('reserve_429'),
};
const other = new Counter('reserve_other');
const serverError = new Counter('reserve_5xx');
const cancel200 = new Counter('cancel_200');
const cancelOther = new Counter('cancel_other');
const cancel5xx = new Counter('cancel_5xx');
const upstream = new Counter('upstream');
const created = [];                                  // 이 VU 가 만든 예약 (CANCEL_RATIO 용, VU 마다 따로)

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.min(MAX_VUS, Math.max(50, RATE)), maxVUs: MAX_VUS,
    },
  },
  thresholds: { 'http_req_duration{name:reserve}': ['p(95)>=0'] },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const member = i % USERS;
  const token = tokens[member];
  if (!token) {
    other.add(1);
    return;
  }
  const target = SCENARIO === 'hot' ? TARGETS[0] : TARGETS[member % TARGETS.length];
  const breadId = SCENARIO === 'hot' ? 1 : 1 + (Math.floor(i / TARGETS.length) % BREADS);
  const res = http.post(`${BASE_URL}/api/v1/reservations`,
      JSON.stringify({ saleDate: target.date, pickupSlotId: target.slot, items: [{ breadId, quantity: 1 }] }),
      {
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': uuidv4() },
        tags: { name: 'reserve' },
      });
  (counters[res.status] || other).add(1);
  if (res.status >= 500) {
    serverError.add(1);
  }
  countUpstream(res);
  if (CANCEL_RATIO > 0 && res.status === 201) {
    created.push({ id: res.json('reservationId'), token });
  }
  if (CANCEL_RATIO > 0 && created.length > 0 && Math.random() < CANCEL_RATIO) {
    const target = created.shift();                  // 실패해도 다시 시도하지 않는다(재고 판정은 DB 상태 기준이라 무관)
    const canceled = http.patch(`${BASE_URL}/api/v1/reservations/${target.id}`,
        JSON.stringify({ status: 'CANCELED' }),
        {
          headers: { 'Content-Type': 'application/merge-patch+json', Authorization: `Bearer ${target.token}` },
          tags: { name: 'cancel' },
        });
    (canceled.status === 200 ? cancel200 : cancelOther).add(1);
    if (canceled.status >= 500) {
      cancel5xx.add(1);
    }
    countUpstream(canceled);
  }
}

function countUpstream(res) {
  if (TRACK_UPSTREAM) {
    upstream.add(1, { upstream: res.headers['X-Upstream'] || 'none' });
  }
}

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const d = data.metrics['http_req_duration{name:reserve}'];
  const seconds = parseFloat(DURATION);          // 처리량 기준은 보낸 구간(초). 끝난 뒤 남은 응답을 기다린 시간은 넣지 않는다
  const sent = ['reserve_201', 'reserve_409', 'reserve_422', 'reserve_429', 'reserve_other'].reduce((s, n) => s + count(n), 0);
  const result = {
    scenario: SCENARIO, rate: RATE, duration: DURATION,
    sent, dropped: count('dropped_iterations'),
    throughput: Math.round((count('reserve_201') / seconds) * 10) / 10,
    created201: count('reserve_201'), conflict409: count('reserve_409'), unprocessable422: count('reserve_422'),
    tooMany429: count('reserve_429'), other: count('reserve_other'), serverError5xx: count('reserve_5xx'),
    cancel: CANCEL_RATIO > 0 ? { ok200: count('cancel_200'), other: count('cancel_other'), serverError5xx: count('cancel_5xx') } : null,
    maxVus: data.metrics.vus_max ? data.metrics.vus_max.values.max : null,
    reserveDurationMs: d ? Object.fromEntries(Object.entries(d.values).map(([k, v]) => [k, Math.round(v * 10) / 10])) : {},
  };
  const out = { stdout: `\nRESULT ${JSON.stringify(result)}\n` };
  if (__ENV.OUT_FILE) {
    out[__ENV.OUT_FILE] = JSON.stringify(data, null, 2);
  }
  return out;
}
