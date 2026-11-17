import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../src/api/client'
import { refreshAccessToken } from '../src/auth/refresh'
import { getSession, setSession } from '../src/auth/session'

const jsonResponse = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const tokenResponse = (accessToken: string) => jsonResponse(200, { accessToken, expiresIn: 1800, mustChangePassword: false })

describe('Access Token 재발급 (Refresh 재사용 감지 때문에 한 번만 보냄)', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    setSession(null)
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('동시에 여러 번 불러도 재발급 요청은 한 번만 나간다', async () => {
    fetchMock.mockResolvedValueOnce(tokenResponse('new-token'))

    const results = await Promise.all([refreshAccessToken(), refreshAccessToken(), refreshAccessToken()])

    expect(results).toEqual([true, true, true])
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(getSession()?.accessToken).toBe('new-token')
  })

  it('끝난 뒤에 다시 부르면 새로 요청한다', async () => {
    fetchMock.mockResolvedValueOnce(tokenResponse('a')).mockResolvedValueOnce(tokenResponse('b'))

    await refreshAccessToken()
    await refreshAccessToken()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(getSession()?.accessToken).toBe('b')
  })

  it('쿠키가 없거나 만료되면(401) 로그아웃 상태가 된다', async () => {
    setSession({ accessToken: 'old', mustChangePassword: false })
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { code: 'UNAUTHENTICATED' }))

    expect(await refreshAccessToken()).toBe(false)
    expect(getSession()).toBeNull()
  })

  it('API 3개가 동시에 401 을 받아도 재발급은 한 번이고, 각 요청은 새 토큰으로 한 번씩 다시 보낸다', async () => {
    setSession({ accessToken: 'expired', mustChangePassword: false })
    fetchMock.mockImplementation(async (url: string, init: RequestInit) => {
      if (url === '/api/v1/auth/tokens/current') return tokenResponse('fresh')
      const auth = (init.headers as Record<string, string>).Authorization
      return auth === 'Bearer fresh' ? jsonResponse(200, { url }) : jsonResponse(401, { code: 'UNAUTHENTICATED' })
    })

    const results = await Promise.all([api('/api/v1/a'), api('/api/v1/b'), api('/api/v1/c')])

    expect(results).toEqual([{ url: '/api/v1/a' }, { url: '/api/v1/b' }, { url: '/api/v1/c' }])
    const reissueCalls = fetchMock.mock.calls.filter(([url]) => url === '/api/v1/auth/tokens/current')
    expect(reissueCalls).toHaveLength(1)
    expect(fetchMock).toHaveBeenCalledTimes(7)   // 401 3번 + 재발급 1번 + 다시 보냄 3번
  })

  it('토큰 없이 보낸 요청의 401 은 재발급하지 않는다 (로그인 실패 등)', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { code: 'INVALID_CREDENTIALS', title: '번호 또는 비밀번호가 틀렸습니다' }))

    await expect(api('/api/v1/auth/tokens', { method: 'POST', body: {}, anonymous: true })).rejects.toMatchObject({
      status: 401,
      code: 'INVALID_CREDENTIALS',
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
