// 로그인·로그아웃·가입 API 와 화면용 훅.
import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState, useSyncExternalStore, type ReactNode } from 'react'
import { api } from '../api/client'
import type { AccessTokenResponse, LoginRequest, SignupRequest } from '../api/types'
import { refreshAccessToken } from './refresh'
import { getSession, setSession, subscribeSession, type Session } from './session'

export function useSession(): Session | null {
  return useSyncExternalStore(subscribeSession, getSession)
}

/**
 * 앱 시작 때 Refresh 쿠키로 로그인 상태를 복구한 뒤 화면을 그린다.
 * 복구 전에 그리면 빵 목록이 비로그인으로 한 번 조회돼(myReservedQty = null) 다시 불러야 한다.
 */
export function SessionRestorer({ children }: { children: ReactNode }) {
  const [restored, setRestored] = useState(false)

  useEffect(() => {
    // StrictMode 가 이 effect 를 두 번 실행해도 refreshAccessToken 이 요청을 하나로 합친다
    refreshAccessToken()
      .catch(() => false)    // 서버에 닿지 않으면 비로그인으로 시작
      .finally(() => setRestored(true))
  }, [])

  if (!restored) return <p className="page-message">불러오는 중…</p>
  return <>{children}</>
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useCallback(async (request: LoginRequest) => {
    const body = await api<AccessTokenResponse>('/api/v1/auth/tokens', { method: 'POST', body: request, anonymous: true })
    setSession({ accessToken: body.accessToken, mustChangePassword: body.mustChangePassword })
    queryClient.clear()   // 비로그인으로 받아 둔 목록(myReservedQty = null)을 버린다
  }, [queryClient])
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useCallback(async () => {
    try {
      await api<void>('/api/v1/auth/tokens/current', { method: 'DELETE', anonymous: true })  // 쿠키로 처리. 실패해도 화면은 로그아웃
    } finally {
      setSession(null)
      queryClient.clear()
    }
  }, [queryClient])
}

export function signUp(request: SignupRequest): Promise<unknown> {
  return api('/api/v1/members', { method: 'POST', body: request, anonymous: true })
}
