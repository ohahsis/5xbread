// 로그인 상태(Access Token)를 메모리에만 둔다.
//
// localStorage·sessionStorage 에 넣지 않는다: XSS 가 생기면 스크립트가 그대로 읽어 간다.
// 새로고침하면 사라지므로, 앱 시작 때 Refresh 쿠키(HttpOnly, JS 가 못 읽음)로 재발급받아 복구한다 (refresh.ts).
// React 밖(api 클라이언트)에서도 읽어야 해서 모듈 변수로 두고, 화면은 subscribe 로 바뀜을 받는다 (useSession).

export type Session = {
  accessToken: string
  /** 임시 비밀번호로 로그인한 상태. 비밀번호를 바꾸기 전에는 대부분의 API 가 403 (AU-06·AU-08) */
  mustChangePassword: boolean
}

let current: Session | null = null
const listeners = new Set<() => void>()

export function getSession(): Session | null {
  return current
}

export function setSession(session: Session | null): void {
  current = session
  listeners.forEach((listener) => listener())
}

export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
