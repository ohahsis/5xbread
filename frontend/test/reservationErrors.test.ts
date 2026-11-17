import { describe, expect, it } from 'vitest'
import { ApiError } from '../src/api/problem'
import { reservationErrorView } from '../src/reservation/reservationErrors'

describe('예약 실패 문구', () => {
  it('409 errors[] 를 빵별 문구로 나눈다 (재고 부족과 1인 한도가 섞인 경우)', () => {
    const error = new ApiError(409, 'OUT_OF_STOCK', '예약 가능한 수량이 부족합니다', [
      { breadId: 1, reason: 'OUT_OF_STOCK', remaining: 2 },
      { breadId: 3, reason: 'PER_USER_LIMIT_EXCEEDED', remaining: 1 },
    ])

    const view = reservationErrorView(error)

    expect(view.byBread.get(1)).toContain('2개까지')
    expect(view.byBread.get(3)).toContain('1개 더')
    expect(view.retryable).toBe(false)
  })

  it('429 는 Retry-After 초를 안내한다', () => {
    const view = reservationErrorView(new ApiError(429, 'TOO_MANY_REQUESTS', '요청이 너무 많습니다', [], 3))
    expect(view.message).toContain('3초')
    expect(view.retryable).toBe(true)
  })

  it('503·연결 실패는 같은 예약으로 다시 보내도 된다고 안내한다', () => {
    expect(reservationErrorView(new ApiError(503, 'SERVICE_UNAVAILABLE', '잠시 후 다시 시도해 주세요')).retryable).toBe(true)
    expect(reservationErrorView(ApiError.network()).retryable).toBe(true)
  })
})
