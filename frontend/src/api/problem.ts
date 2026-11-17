// 서버 오류 응답(RFC 9457 ProblemDetail + code)을 화면에서 다루기 쉬운 ApiError 로 바꾼다.
//
// 응답 예 (409 OUT_OF_STOCK):
//   { "status": 409, "code": "OUT_OF_STOCK", "title": "예약 가능한 수량이 부족합니다",
//     "errors": [{ "breadId": 3, "reason": "OUT_OF_STOCK", "remaining": 2 }] }
// errors[] 의 항목 모양은 오류마다 다르다 (400 검증: field·message / 409 재고: breadId·reason·remaining / 422 BREAD_NOT_AVAILABLE: breadId)

export type ProblemErrorItem = {
  field?: string | null
  code?: string
  message?: string
  breadId?: number
  reason?: string
  remaining?: number
}

/** 응답을 받지 못한 경우(연결 실패·끊김)의 code. status 는 0 */
export const NETWORK_ERROR = 'NETWORK_ERROR'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly errors: ProblemErrorItem[]
  /** 429·423 의 Retry-After(초). 없으면 null */
  readonly retryAfterSeconds: number | null

  constructor(status: number, code: string, message: string, errors: ProblemErrorItem[] = [], retryAfterSeconds: number | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.errors = errors
    this.retryAfterSeconds = retryAfterSeconds
  }

  static network(): ApiError {
    return new ApiError(0, NETWORK_ERROR, '서버에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요')
  }
}

/** fetch 응답(2xx 아님)을 ApiError 로. 본문이 ProblemDetail 이 아니면(Nginx 502 HTML 등) 상태 코드만으로 만든다 */
export async function toApiError(response: Response): Promise<ApiError> {
  const retryAfter = response.headers.get('Retry-After')
  const retryAfterSeconds = retryAfter !== null && /^\d+$/.test(retryAfter) ? Number(retryAfter) : null

  const contentType = response.headers.get('Content-Type') ?? ''
  if (contentType.includes('json')) {
    try {
      const body = await response.json()
      const message = body.detail || body.title || `요청이 실패했습니다 (${response.status})`
      return new ApiError(response.status, body.code ?? `HTTP_${response.status}`, message, body.errors ?? [], retryAfterSeconds)
    } catch {
      // 본문이 깨졌으면 아래 기본값으로
    }
  }
  return new ApiError(response.status, `HTTP_${response.status}`, defaultMessage(response.status), [], retryAfterSeconds)
}

function defaultMessage(status: number): string {
  if (status === 502 || status === 504) return '서버 응답이 늦거나 끊겼습니다. 잠시 후 다시 시도해 주세요'
  if (status === 503) return '서버를 잠시 사용할 수 없습니다. 잠시 후 다시 시도해 주세요'
  return `요청이 실패했습니다 (${status})`
}

/** 400 검증 오류의 errors[] 를 { 필드 이름: 문구 } 로. 입력칸 아래에 표시할 때 쓴다 */
export function fieldErrorsOf(error: unknown): Record<string, string> {
  const result: Record<string, string> = {}
  if (error instanceof ApiError) {
    for (const item of error.errors) {
      if (item.field && item.message) result[item.field] = item.message
    }
  }
  return result
}

export function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return '알 수 없는 오류가 발생했습니다'
}
