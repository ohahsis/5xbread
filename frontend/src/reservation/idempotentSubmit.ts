// C-04 예약의 Idempotency-Key 규칙. React 와 무관한 순수 클래스.
//
// - 키는 "보낼 내용"(판매일·픽업 시간대·빵별 수량)에 묶는다. 같은 내용을 다시 보내면 같은 키를 쓴다.
//   응답을 못 받은 요청(연결 끊김·502·504)을 다시 보내도 서버가 이미 처리했다면 같은 201 을 돌려줘 두 번 예약되지 않는다.
// - 내용이 바뀌면 새 키. 같은 키로 다른 내용을 보내면 서버가 422 IDEMPOTENCY_KEY_MISMATCH 로 거절한다.
// - 성공(201)하면 키를 버린다. 다음 예약은 같은 내용이어도 새 예약이다.
// - 보내는 중에 또 누르면 새 요청을 만들지 않고 진행 중인 요청을 그대로 돌려준다 (두 번 누름).
//
// 객체는 예약 화면 하나에 하나(useRef). 화면을 떠났다 돌아오면 새 키로 시작한다.

export type ReservationDraft = {
  saleDate: string
  pickupSlotId: number
  items: { breadId: number; quantity: number }[]
}

export class IdempotentSubmitter<R> {
  private key: string | null = null
  private fingerprint: string | null = null
  private pending: Promise<R> | null = null
  private readonly newKey: () => string

  constructor(newKey: () => string = () => crypto.randomUUID()) {
    this.newKey = newKey
  }

  submit(draft: ReservationDraft, send: (draft: ReservationDraft, idempotencyKey: string) => Promise<R>): Promise<R> {
    if (this.pending) return this.pending

    const key = this.keyFor(draft)
    this.pending = send(draft, key)
      .then((result) => {
        this.clearKey()
        return result
      })
      .finally(() => {
        this.pending = null
      })
    return this.pending
  }

  /** 지금 내용으로 보낼 때 쓸 키 (화면 표시·테스트용). 내용이 바뀌었으면 새로 만든다 */
  keyFor(draft: ReservationDraft): string {
    const fingerprint = fingerprintOf(draft)
    if (this.key === null || fingerprint !== this.fingerprint) {
      this.key = this.newKey()
      this.fingerprint = fingerprint
    }
    return this.key
  }

  private clearKey(): void {
    this.key = null
    this.fingerprint = null
  }
}

/** 같은 내용이면 같은 문자열. 빵 순서는 상관없게 breadId 로 정렬한다 */
function fingerprintOf(draft: ReservationDraft): string {
  const items = [...draft.items].sort((a, b) => a.breadId - b.breadId).map((item) => [item.breadId, item.quantity])
  return JSON.stringify([draft.saleDate, draft.pickupSlotId, items])
}
