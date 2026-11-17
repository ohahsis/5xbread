#!/usr/bin/env bash
# infra/nginx/retry-check.sh : nginx.conf 의 재시도(proxy_next_upstream) 동작 확인 (decisions.md 6장 "Nginx 재시도")
# 실행(레포 루트에서): ./infra/nginx/retry-check.sh   → 하나라도 기대와 다르면 exit 1. 필요한 것: Docker, nginx:1.27 이미지
#
# 실제 nginx.conf 를 그대로 띄우고, app1~3 자리에 "요청을 받고 응답 없이 연결을 끊는" 가짜 앱(nginx return 444)을 둔다.
# 앱이 요청을 받은 뒤 죽은 상황이다. Nginx 가 다음 앱으로 다시 보내면 가짜 앱들이 받은 횟수가 2가 된다.
# - 재발급 PUT·비밀번호 변경 PUT·탈퇴 DELETE: proxy_next_upstream off → 1번
# - 다른 PUT(대조군): 멱등으로 보고 다시 보냄 → 2번 (proxy_next_upstream_tries 2)
# - POST(대조군): 앱에 보낸 뒤에는 다시 보내지 않음 → 1번
# max_fails=1 이라 실패한 앱은 10초 빠지므로, 경우마다 Nginx 를 새로 띄워 상태를 지운다
set -euo pipefail

cd "$(dirname "$0")/../.."
IMAGE=nginx:1.27
NET="retry-check-$$"
STUB_CONF=$(mktemp)
trap 'docker rm -f $(docker ps -aq --filter "label=retry-check=$$") >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; rm -f "$STUB_CONF"' EXIT

cat > "$STUB_CONF" <<'CONF'
events {}
http {
  log_format hit 'HIT $request';
  access_log /dev/stdout hit;
  server {
    listen 8080;
    location / { return 444; }   # 응답 없이 연결을 끊는다 (요청은 받음)
  }
}
CONF
chmod 644 "$STUB_CONF"

docker network create "$NET" >/dev/null
for app in app1 app2 app3; do
  docker run -d --label "retry-check=$$" --network "$NET" --network-alias "$app" --name "$NET-$app" \
    -v "$STUB_CONF:/etc/nginx/nginx.conf:ro" "$IMAGE" >/dev/null
done

# 설정 문법 확인 (app1~3 이름이 풀려야 하므로 같은 네트워크에서)
docker run --rm --network "$NET" -v "$PWD/infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" "$IMAGE" nginx -t 2>&1 | tail -1

FAILED=0
CASE=0
check() {   # check 메서드 경로 기대횟수
  local method=$1 path=$2 expected=$3
  CASE=$((CASE + 1))
  local marker="retry-check-case-$CASE"
  local lb
  lb=$(docker run -d --label "retry-check=$$" --network "$NET" -p 127.0.0.1::80 \
    -v "$PWD/infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" "$IMAGE")
  local port=""
  for _ in $(seq 1 50); do
    port=$(docker port "$lb" 80/tcp 2>/dev/null | head -1 | cut -d: -f2 || true)
    [[ -n "$port" ]] && curl -s -o /dev/null "http://127.0.0.1:$port/assets/none" && break
    sleep 0.1
  done
  local status
  status=$(curl -s -o /dev/null -w '%{http_code}' -X "$method" -H 'Content-Type: application/json' -d '{}' \
    "http://127.0.0.1:$port$path?$marker")
  sleep 0.3
  local hits=0
  for app in app1 app2 app3; do
    hits=$((hits + $(docker logs "$NET-$app" 2>/dev/null | grep -c "$marker" || true)))
  done
  docker rm -f "$lb" >/dev/null
  if [[ "$hits" -eq "$expected" ]]; then
    echo "PASS  $method $path → 앱 도착 ${hits}번 (Nginx 응답 $status)"
  else
    echo "FAIL  $method $path → 앱 도착 ${hits}번, 기대 ${expected}번 (Nginx 응답 $status)"
    FAILED=1
  fi
}

check PUT    /api/v1/auth/tokens/current        1
check DELETE /api/v1/auth/tokens/current        1
check PUT    /api/v1/members/me/password        1
check DELETE /api/v1/members/me                 1
check PUT    /api/v1/admin/sale-dates/2026-12-01 2   # 대조군: 다른 PUT 은 다시 보낸다
check POST   /api/v1/auth/tokens                1   # 대조군: POST 는 다시 보내지 않는다

if [[ $FAILED -ne 0 ]]; then
  echo "결과: 실패"
  exit 1
fi
echo "결과: 통과"
