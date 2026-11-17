// Access Token 재발급 (PUT /api/v1/auth/tokens/current, Refresh 쿠키 사용).
//
// 동시에 여러 번 불려도 서버로는 한 번만 보낸다 (single-flight).
// 서버는 Refresh Token 을 회전시키고, 이미 쓴 토큰이 다시 오면 "재사용"으로 보고 그 회원의 토큰을 전부 폐기한다
// (decisions.md 6장 "13-6 Refresh Token 재사용 감지"). 그래서 같은 쿠키로 재발급을 두 번 보내면 로그아웃된다.
// 예: 화면의 API 3개가 동시에 401 을 받음, 개발 모드 StrictMode 가 앱 시작 effect 를 두 번 실행함.
import { ApiError } from '../api/problem'
import type { AccessTokenResponse } from '../api/types'
import { setSession } from './session'

export const REISSUE_URL = '/api/v1/auth/tokens/current'

let inFlight: Promise<boolean> | null = null

/** 재발급에 성공하면 true(세션 갱신), 쿠키가 없거나 만료·폐기됐으면 false(세션 비움). 연결 실패는 ApiError 로 던진다 */
export function refreshAccessToken(): Promise<boolean> {
  if (inFlight === null) {
    inFlight = reissue().finally(() => {
      inFlight = null
    })
  }
  return inFlight
}

async function reissue(): Promise<boolean> {
  let response: Response
  try {
    response = await fetch(REISSUE_URL, { method: 'PUT', credentials: 'same-origin' })
  } catch {
    throw ApiError.network()   // 연결 실패는 로그아웃으로 보지 않는다 (세션 유지)
  }
  if (!response.ok) {
    setSession(null)
    return false
  }
  const body: AccessTokenResponse = await response.json()
  setSession({ accessToken: body.accessToken, mustChangePassword: body.mustChangePassword })
  return true
}
