import { describe, expect, it } from 'vitest'
import { ApiError } from '../src/api/problem'
import { IdempotentSubmitter, type ReservationDraft } from '../src/reservation/idempotentSubmit'

const draft = (quantity = 2): ReservationDraft => ({
  saleDate: '2026-11-20',
  pickupSlotId: 1,
  items: [{ breadId: 1, quantity }, { breadId: 2, quantity: 1 }],
})

/** key-1, key-2, ... 순서로 키를 만든다 */
function sequentialKeys() {
  let count = 0
  return () => `key-${++count}`
}

/** 보낸 키를 기록하고, 정해 둔 결과를 차례로 돌려주는 가짜 전송 */
function fakeSend(results: Array<'ok' | 'network' | 'conflict'>) {
  const sentKeys: string[] = []
  const send = async (_: ReservationDraft, key: string) => {
    sentKeys.push(key)
    const result = results.shift()
    if (result === 'network') throw ApiError.network()
    if (result === 'conflict') throw new ApiError(409, 'OUT_OF_STOCK', '예약 가능한 수량이 부족합니다')
    return { reservationId: sentKeys.length }
  }
  return { send, sentKeys }
}

describe('IdempotentSubmitter: 재시도에는 같은 Idempotency-Key', () => {
  it('응답을 못 받은 요청을 같은 내용으로 다시 보내면 같은 키를 쓴다', async () => {
    const submitter = new IdempotentSubmitter<{ reservationId: number }>(sequentialKeys())
    const { send, sentKeys } = fakeSend(['network', 'network', 'ok'])

    await expect(submitter.submit(draft(), send)).rejects.toThrow(ApiError)
    await expect(submitter.submit(draft(), send)).rejects.toThrow(ApiError)
    await submitter.submit(draft(), send)

    expect(sentKeys).toEqual(['key-1', 'key-1', 'key-1'])
  })

  it('빵 순서만 바뀐 같은 내용이면 같은 키다', async () => {
    const submitter = new IdempotentSubmitter<{ reservationId: number }>(sequentialKeys())
    const reordered: ReservationDraft = { ...draft(), items: [...draft().items].reverse() }

    expect(submitter.keyFor(draft())).toBe(submitter.keyFor(reordered))
  })

  it('수량을 바꾸면 새 키를 만든다 (같은 키로 다른 내용을 보내면 서버가 422 로 거절)', async () => {
    const submitter = new IdempotentSubmitter<{ reservationId: number }>(sequentialKeys())
    const { send, sentKeys } = fakeSend(['conflict', 'ok'])

    await expect(submitter.submit(draft(5), send)).rejects.toThrow(ApiError)
    await submitter.submit(draft(2), send)

    expect(sentKeys).toEqual(['key-1', 'key-2'])
  })

  it('성공한 뒤에는 같은 내용이어도 새 키로 보낸다 (새 예약)', async () => {
    const submitter = new IdempotentSubmitter<{ reservationId: number }>(sequentialKeys())
    const { send, sentKeys } = fakeSend(['ok', 'ok'])

    await submitter.submit(draft(), send)
    await submitter.submit(draft(), send)

    expect(sentKeys).toEqual(['key-1', 'key-2'])
  })

  it('보내는 중에 다시 누르면 요청을 새로 만들지 않고 진행 중인 결과를 돌려준다', async () => {
    const submitter = new IdempotentSubmitter<{ reservationId: number }>(sequentialKeys())
    const { send, sentKeys } = fakeSend(['ok'])

    const first = submitter.submit(draft(), send)
    const second = submitter.submit(draft(), send)

    expect(second).toBe(first)
    await first
    expect(sentKeys).toEqual(['key-1'])
  })

  it('기본 키는 UUID 형식이다 (서버가 UUID 가 아니면 400)', () => {
    const submitter = new IdempotentSubmitter()
    expect(submitter.keyFor(draft())).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })
})
