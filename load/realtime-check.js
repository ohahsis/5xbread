// load/realtime-check.js : 앱 3개 + Nginx 에서 잔여 실시간 갱신(C-03)이 다른 앱에 붙은 손님에게도 가는지 확인한다
// 실행: ./load/realtime-check.sh (seed.sql 로 판매일·손님을 넣고 k6 컨테이너로 이 스크립트를 돈다)
//
// - listen: STOMP 리스너 LISTENERS 개가 Nginx(8081)를 거쳐 /ws 에 붙어 잔여 토픽을 구독한다. 라운드 로빈이라 앱 3개에 나뉘어 붙는다.
//           붙은 앱은 101 응답의 X-Upstream 으로 안다. 토큰 없이(익명) 연결한다
// - write : 3초 뒤 손님 1명이 예약 RESERVATIONS 건을 1초 간격으로 보내고(처리 앱은 X-Upstream), 마지막에 목록(스냅샷)을 읽는다
// 각 리스너가 받은 메시지 수·버전·마지막 잔여를 RESULT 줄로 남기고, 판정은 realtime-check.sh 가 한다
import ws from 'k6/ws';
import http from 'k6/http';
import { sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://nginx:8081';
const WS_URL = BASE_URL.replace(/^http/, 'ws') + '/ws';
const SALE_DATE = __ENV.SALE_DATE;
const PICKUP_SLOT_ID = Number(__ENV.PICKUP_SLOT_ID);
const BREAD_ID = Number(__ENV.BREAD_ID || 1);
const PASSWORD = 'Load-test-2026!';            // load/seed.sql 의 부하 테스트 손님 공통 비밀번호
const LISTENERS = 6;
const RESERVATIONS = 3;
const LISTEN_MS = 12000;

export const options = {
  scenarios: {
    listen: { executor: 'per-vu-iterations', vus: LISTENERS, iterations: 1, exec: 'listen' },
    write: { executor: 'per-vu-iterations', vus: 1, iterations: 1, exec: 'write', startTime: '3s' },
  },
};

// STOMP 프레임: 명령, 헤더, 빈 줄, 본문, NUL
function frame(command, headers, body = '') {
  const lines = Object.entries(headers).map(([k, v]) => `${k}:${v}`);
  return `${command}\n${lines.join('\n')}\n\n${body}\0`;
}

export function listen() {
  const messages = [];
  const errors = [];
  const res = ws.connect(WS_URL, {}, (socket) => {
    socket.on('open', () => socket.send(frame('CONNECT', { 'accept-version': '1.2', host: 'nginx', 'heart-beat': '0,0' })));
    socket.on('message', (data) => {
      if (data.startsWith('CONNECTED')) {
        socket.send(frame('SUBSCRIBE', { id: 'stock', destination: `/topic/sale-dates/${SALE_DATE}/stock` }));
      } else if (data.startsWith('MESSAGE')) {
        messages.push(JSON.parse(data.substring(data.indexOf('\n\n') + 2).replace(/\0$/, '')));
      } else if (data.startsWith('ERROR')) {
        errors.push(data);
      }
    });
    socket.setTimeout(() => socket.close(), LISTEN_MS);
  });
  const last = {};
  messages.forEach((m) => m.changes.forEach((c) => { last[c.breadId] = c.remaining; }));
  console.log('RESULT listen ' + JSON.stringify({
    upstream: res.headers['X-Upstream'], status: res.status, errors: errors.length,
    versions: messages.map((m) => m.version), remaining: last[BREAD_ID],
  }));
}

export function write() {
  const login = http.post(`${BASE_URL}/api/v1/auth/tokens`, JSON.stringify({ phone: '01000000001', password: PASSWORD }),
      { headers: { 'Content-Type': 'application/json' } });
  const token = login.json('accessToken');
  const upstreams = [];
  for (let i = 0; i < RESERVATIONS; i++) {
    const r = http.post(`${BASE_URL}/api/v1/reservations`,
        JSON.stringify({ saleDate: SALE_DATE, pickupSlotId: PICKUP_SLOT_ID, items: [{ breadId: BREAD_ID, quantity: 1 }] }),
        { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': uuidv4() } });
    upstreams.push(`${r.status}@${r.headers['X-Upstream']}`);
    sleep(1);
  }
  const list = http.get(`${BASE_URL}/api/v1/sale-dates/${SALE_DATE}/breads`);
  const bread = list.json('breads').find((b) => b.breadId === BREAD_ID);
  console.log('RESULT write ' + JSON.stringify({
    login: login.status, reservations: upstreams, listVersion: list.json('version'), listRemaining: bread.remaining,
  }));
}

// Idempotency-Key 는 UUID 형식만 받는다 (reserve.js 와 같은 방식)
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
