// load/login.js : 일정 부하(steady.sh) 전에 손님 USERS 명을 실제 로그인 API 로 로그인시켜 Access Token 을 파일로 남긴다
//
// 로그인(BCrypt)이 측정 구간에 섞이지 않게 따로 돌린다. 테스트용 인증 우회는 없다.
// 토큰은 steady.sh 가 만든 임시 폴더(/work)에만 쓰고, steady.sh 가 끝날 때 지운다. 출력에는 남기지 않는다.
// 환경 변수: BASE_URL, USERS(기본 10000), BATCH(동시 로그인 수, 기본 200)
import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://nginx:8081';
const USERS = Number(__ENV.USERS || 10000);
const BATCH = Number(__ENV.BATCH || 200);
const PASSWORD = 'Load-test-2026!';            // load/seed-steady.sql 의 손님 공통 비밀번호

export const options = {
  setupTimeout: '15m',
  scenarios: { noop: { executor: 'shared-iterations', vus: 1, iterations: 1 } },
};

// 손님 i (1 ~ USERS) 의 번호. seed-steady.sql 과 같은 규칙
function phone(i) {
  return `0100${String(1000000 + i)}`;
}

export function setup() {
  const tokens = [];
  let failed = 0;
  for (let start = 1; start <= USERS; start += BATCH) {
    const requests = [];
    for (let i = start; i < Math.min(start + BATCH, USERS + 1); i++) {
      requests.push(['POST', `${BASE_URL}/api/v1/auth/tokens`, JSON.stringify({ phone: phone(i), password: PASSWORD }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } }]);
    }
    for (const res of http.batch(requests)) {
      if (res.status === 201) {
        tokens.push(res.json('accessToken'));
      } else {
        tokens.push(null);
        failed++;
      }
    }
  }
  return { tokens, failed };
}

export default function () {
}

export function handleSummary(data) {
  const { tokens, failed } = data.setup_data;
  return {
    stdout: `\nLOGIN ${JSON.stringify({ users: USERS, failed })}\n`,
    '/work/tokens.json': JSON.stringify(tokens),
  };
}
