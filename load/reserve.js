// load/reserve.js : 예약 오픈 순간 동시 예약 (N-21). 실행은 run.sh (compose.full.yaml 의 k6 컨테이너)
//
// 손님 1,000명(VU 1,000개)이 각자 로그인한 뒤, 모두 같은 시각(START_AT)까지 기다렸다가 같은 빵 1개를 동시에 예약한다.
// - 로그인은 실제 API(POST /api/v1/auth/tokens)로 한다. 테스트용 인증 우회는 없다
// - 로그인(BCrypt)을 예약과 같은 순간에 하지 않도록 기다리는 시간을 두어, 예약 요청만 한꺼번에 몰리게 한다
// - 토큰은 VU 안에서만 쓰고 출력하지 않는다
//
// CANCEL=1 (run.sh --with-cancel): 예약과 취소를 섞는다. 201 을 받은 손님 중 번호가 홀수면 바로 취소하고,
// 409 를 받은 손님은 1~2초 뒤 한 번 더 예약한다(취소표 줍기). 취소의 Redis 복구(아웃박스)와 예약이 겹친다
//
// 환경 변수: BASE_URL, SALE_DATE, PICKUP_SLOT_ID, BREAD_ID, APP_IPS("app1=172.18.0.5,app2=..."), CANCEL
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://nginx:8081';   // 8081: X-Upstream 을 붙여 주는 내부 포트 (nginx.conf)
const SALE_DATE = __ENV.SALE_DATE;
const PICKUP_SLOT_ID = Number(__ENV.PICKUP_SLOT_ID);
const BREAD_ID = Number(__ENV.BREAD_ID || 1);
const CANCEL = __ENV.CANCEL === '1';
const PASSWORD = 'Load-test-2026!';            // load/seed.sql 의 부하 테스트 손님 공통 비밀번호
const USERS = 1000;
const LOGIN_WINDOW_SECONDS = 60;               // 이 시간 안에 모두 로그인하고, 끝나는 순간 다 같이 예약

const APPS = Object.fromEntries((__ENV.APP_IPS || '').split(',').filter(Boolean)
    .map((pair) => pair.split('=')).map(([name, ip]) => [`${ip}:8080`, name]));

const statusCounters = {
  201: new Counter('reserve_201'),
  409: new Counter('reserve_409'),
};
const otherStatus = new Counter('reserve_other');      // 201·409 가 아닌 응답 (500 포함)
const serverError = new Counter('reserve_5xx');
const loginFailed = new Counter('login_failed');
const appCounters = {
  app1: new Counter('upstream_app1'),
  app2: new Counter('upstream_app2'),
  app3: new Counter('upstream_app3'),
};
const unknownApp = new Counter('upstream_unknown');
const cancel200 = new Counter('cancel_200');
const cancelOther = new Counter('cancel_other');
const retryCounters = {
  201: new Counter('retry_201'),
  409: new Counter('retry_409'),
};
const retryOther = new Counter('retry_other');

export const options = {
  scenarios: {
    open: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '3m' },
  },
  // 태그별 시간을 요약에 따로 보이게 한다
  thresholds: {
    'http_req_duration{name:reserve}': ['p(95)>=0'],
    'http_req_duration{name:login}': ['p(95)>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const vu = exec.vu.idInTest;                 // 1 ~ 1000
  const phone = `0100000${String(vu).padStart(4, '0')}`;
  const login = http.post(`${BASE_URL}/api/v1/auth/tokens`, JSON.stringify({ phone, password: PASSWORD }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  if (!check(login, { 'login 201': (r) => r.status === 201 })) {
    loginFailed.add(1);
    return;
  }
  const token = login.json('accessToken');

  const startAt = exec.scenario.startTime + LOGIN_WINDOW_SECONDS * 1000;
  const wait = (startAt - Date.now()) / 1000;
  if (wait > 0) {
    sleep(wait);
  }

  const res = reserve(token, 'reserve');
  check(res, { '201 or 409': (r) => r.status === 201 || r.status === 409 });

  (statusCounters[res.status] || otherStatus).add(1);
  if (res.status >= 500) {
    serverError.add(1);
  }
  if (!statusCounters[res.status]) {
    console.warn(`예상 밖 응답 status=${res.status} code=${safeCode(res)}`);
  }
  const app = APPS[res.headers['X-Upstream']];
  (appCounters[app] || unknownApp).add(1);

  if (!CANCEL) {
    return;
  }
  if (res.status === 201 && vu % 2 === 1) {
    const canceled = http.patch(`${BASE_URL}/api/v1/reservations/${res.json('reservationId')}`,
        JSON.stringify({ status: 'CANCELED' }),
        { headers: { 'Content-Type': 'application/merge-patch+json', Authorization: `Bearer ${token}` },
          tags: { name: 'cancel' } });
    (canceled.status === 200 ? cancel200 : cancelOther).add(1);
    countServerError(canceled, 'cancel');
  } else if (res.status === 409) {
    sleep(1 + Math.random());
    const retry = reserve(token, 'retry');
    (retryCounters[retry.status] || retryOther).add(1);
    countServerError(retry, 'retry');
  }
}

function reserve(token, name) {
  return http.post(`${BASE_URL}/api/v1/reservations`,
      JSON.stringify({ saleDate: SALE_DATE, pickupSlotId: PICKUP_SLOT_ID, items: [{ breadId: BREAD_ID, quantity: 1 }] }),
      {
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': uuidv4() },
        tags: { name },
      });
}

function countServerError(res, name) {
  if (res.status >= 500) {
    serverError.add(1);
    console.warn(`${name} 5xx status=${res.status} code=${safeCode(res)}`);
  }
}

function safeCode(res) {
  try {
    return res.json('code');
  } catch (e) {
    return '-';
  }
}

// Idempotency-Key 는 UUID 형식만 받는다. 외부 jslib 를 받지 않으려고 직접 만든다
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const trend = (name) => {
    const m = data.metrics[name];
    return m ? Object.fromEntries(Object.entries(m.values).map(([k, v]) => [k, Math.round(v * 10) / 10])) : {};
  };
  const result = {
    users: USERS,
    loginFailed: count('login_failed'),
    reserve: {
      created201: count('reserve_201'),
      conflict409: count('reserve_409'),
      other: count('reserve_other'),
      serverError5xx: count('reserve_5xx'),
    },
    upstream: {
      app1: count('upstream_app1'),
      app2: count('upstream_app2'),
      app3: count('upstream_app3'),
      unknown: count('upstream_unknown'),
    },
    cancel: CANCEL ? {
      ok200: count('cancel_200'),
      cancelOther: count('cancel_other'),
      retry201: count('retry_201'),
      retry409: count('retry_409'),
      retryOther: count('retry_other'),
    } : null,
    reserveDurationMs: trend('http_req_duration{name:reserve}'),
    loginDurationMs: trend('http_req_duration{name:login}'),
  };
  return { stdout: `\nRESULT ${JSON.stringify(result)}\n` };
}
