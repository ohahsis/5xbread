// fetch 래퍼. 모든 API 호출은 여기를 지난다.
//
// 1. 로그인 상태면 Authorization: Bearer {Access Token}
// 2. 토큰을 붙였는데 401 이면 재발급(single-flight) 후 한 번만 다시 보낸다. 재발급도 실패하면 로그아웃 상태가 된다
// 3. 2xx 가 아니면 ApiError 를 던진다 (problem.ts)
import { refreshAccessToken } from '../auth/refresh'
import { getSession } from '../auth/session'
import { ApiError, toApiError } from './problem'

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  /** 기본 application/json. 취소 PATCH 는 application/merge-patch+json */
  contentType?: string
  headers?: Record<string, string>
  /** true 면 토큰을 붙이지 않는다. 로그인·가입용: 임시 비밀번호(pcr) 토큰을 붙이면 로그인도 403 이 된다 (decisions.md 6장 "AU-06·AU-08 변경 강제") */
  anonymous?: boolean
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const sentToken = !options.anonymous && getSession() !== null
  let response = await send(path, options)

  if (response.status === 401 && sentToken) {
    const refreshed = await refreshAccessToken()
    if (refreshed) {
      response = await send(path, options)
    }
  }

  if (!response.ok) {
    throw await toApiError(response)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

async function send(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  const session = options.anonymous ? null : getSession()
  if (session) {
    headers.Authorization = `Bearer ${session.accessToken}`
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = options.contentType ?? 'application/json'
  }

  try {
    return await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: 'same-origin',
    })
  } catch {
    throw ApiError.network()
  }
}

/** 쿼리 문자열. 값이 undefined·null·'' 인 항목은 뺀다 */
export function withQuery(path: string, params: Record<string, string | number | null | undefined>): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') query.set(key, String(value))
  }
  const text = query.toString()
  return text ? `${path}?${text}` : path
}
