// C-04 예약 실패를 화면 문구로. 빵 줄 아래에 붙일 문구(breadId 별)와 화면 위쪽 안내 하나로 나눈다.
import { ApiError, NETWORK_ERROR } from '../api/problem'

export type ReservationErrorView = {
  /** 화면 위쪽 안내 */
  message: string
  /** breadId → 그 빵 줄 아래 문구 */
  byBread: Map<number, string>
  /** 같은 내용으로 다시 보내면 되는 오류인지 (같은 Idempotency-Key 로 재시도해도 안전함을 함께 안내) */
  retryable: boolean
}

export function reservationErrorView(error: unknown): ReservationErrorView {
  const byBread = new Map<number, string>()
  if (!(error instanceof ApiError)) {
    return { message: '알 수 없는 오류가 발생했습니다', byBread, retryable: false }
  }

  switch (error.code) {
    case 'OUT_OF_STOCK':
    case 'PER_USER_LIMIT_EXCEEDED':
      // errors[] 에는 빵별 사유가 전부 들어 있다. 재고 부족과 1인 한도 초과가 섞일 수 있다
      for (const item of error.errors) {
        if (item.breadId === undefined) continue
        byBread.set(item.breadId, item.reason === 'OUT_OF_STOCK'
          ? `재고가 부족합니다. ${item.remaining ?? 0}개까지 예약할 수 있습니다`
          : `1인 한도를 넘었습니다. ${item.remaining ?? 0}개 더 예약할 수 있습니다`)
      }
      return { message: `${error.message}. 수량을 줄여 다시 예약해 주세요`, byBread, retryable: false }

    case 'BREAD_NOT_AVAILABLE':
      for (const item of error.errors) {
        if (item.breadId !== undefined) byBread.set(item.breadId, '이 판매일에 예약할 수 없는 빵입니다')
      }
      return { message: error.message, byBread, retryable: false }

    case 'IDEMPOTENCY_IN_PROGRESS':
      return { message: '앞서 보낸 같은 예약을 처리하고 있습니다. 잠시 후 다시 눌러 주세요', byBread, retryable: true }

    case 'TOO_MANY_REQUESTS': {
      const wait = error.retryAfterSeconds ?? 1
      return { message: `요청이 너무 많습니다. ${wait}초 뒤에 다시 시도해 주세요`, byBread, retryable: true }
    }
  }

  if (error.code === NETWORK_ERROR || error.status === 502 || error.status === 503 || error.status === 504) {
    return {
      message: `${error.message}. 다시 눌러도 같은 예약으로 처리되어 두 번 예약되지 않습니다`,
      byBread,
      retryable: true,
    }
  }
  return { message: error.message, byBread, retryable: false }
}
