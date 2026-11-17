import { describe, expect, it } from 'vitest'
import { StockSync } from '../src/realtime/stockSync'

const breads = (a: number, b: number) => [{ breadId: 1, remaining: a }, { breadId: 2, remaining: b }]

describe('StockSync: 구독 → 버퍼링 → 목록 → 큰 버전만 적용 (decisions.md 6장 "C-03 스냅샷")', () => {
  it('목록을 받기 전 메시지는 버퍼에 쌓고 화면을 바꾸지 않는다', () => {
    const sync = new StockSync()
    sync.startBuffering()

    expect(sync.onMessage({ version: 5, changes: [{ breadId: 1, remaining: 7 }] })).toBe(false)
    expect(sync.remainingOf(1)).toBeUndefined()
    expect(sync.version).toBeNull()
  })

  it('버퍼에 있던 메시지 중 목록 version 이하는 버리고 큰 것만 적용한다', () => {
    const sync = new StockSync()
    sync.startBuffering()
    sync.onMessage({ version: 9, changes: [{ breadId: 1, remaining: 8 }] })    // 목록에 이미 반영된 변경
    sync.onMessage({ version: 10, changes: [{ breadId: 1, remaining: 7 }] })   // 목록에 이미 반영된 변경
    sync.onMessage({ version: 11, changes: [{ breadId: 2, remaining: 3 }] })   // 목록보다 새로운 변경

    sync.onSnapshot(10, breads(7, 5))

    expect(sync.remainingOf(1)).toBe(7)
    expect(sync.remainingOf(2)).toBe(3)
    expect(sync.version).toBe(11)
  })

  it('버퍼는 도착 순서가 아니라 버전 순서로 적용한다', () => {
    const sync = new StockSync()
    sync.startBuffering()
    sync.onMessage({ version: 13, changes: [{ breadId: 1, remaining: 2 }] })
    sync.onMessage({ version: 12, changes: [{ breadId: 1, remaining: 4 }] })

    sync.onSnapshot(10, breads(6, 5))

    expect(sync.remainingOf(1)).toBe(2)
    expect(sync.version).toBe(13)
  })

  it('목록 뒤에는 바로 적용하고, 이미 적용한 버전 이하는 버린다', () => {
    const sync = new StockSync()
    sync.startBuffering()
    sync.onSnapshot(10, breads(7, 5))

    expect(sync.onMessage({ version: 11, changes: [{ breadId: 1, remaining: 6 }] })).toBe(true)
    expect(sync.onMessage({ version: 11, changes: [{ breadId: 1, remaining: 99 }] })).toBe(false)   // 같은 버전
    expect(sync.onMessage({ version: 9, changes: [{ breadId: 1, remaining: 99 }] })).toBe(false)    // 늦게 온 옛 버전

    expect(sync.remainingOf(1)).toBe(6)
  })

  it('재연결하면 다시 버퍼링하고, 새 목록을 기준으로 다시 비교한다', () => {
    const sync = new StockSync()
    sync.startBuffering()
    sync.onSnapshot(10, breads(7, 5))
    sync.onMessage({ version: 11, changes: [{ breadId: 1, remaining: 6 }] })

    // 끊긴 동안 버전 12~20 의 변경을 놓쳤다
    sync.startBuffering()
    expect(sync.remainingOf(1)).toBe(6)   // 목록이 오기 전까지 화면 값은 그대로
    sync.onMessage({ version: 20, changes: [{ breadId: 1, remaining: 1 }] })   // 목록에 반영된 변경
    sync.onMessage({ version: 21, changes: [{ breadId: 2, remaining: 0 }] })
    sync.onSnapshot(20, breads(1, 2))

    expect(sync.remainingOf(1)).toBe(1)
    expect(sync.remainingOf(2)).toBe(0)
    expect(sync.version).toBe(21)
  })

  it('목록 version 0(Redis 장애로 DB 값 응답)이면 그 뒤 메시지를 모두 적용한다', () => {
    const sync = new StockSync()
    sync.startBuffering()
    sync.onMessage({ version: 1, changes: [{ breadId: 1, remaining: 4 }] })
    sync.onSnapshot(0, breads(5, 5))

    expect(sync.remainingOf(1)).toBe(4)
  })

  it('실시간 반영 중 받은 목록이 화면보다 오래된 버전이면 잔여를 덮지 않는다', () => {
    const sync = new StockSync()
    sync.startBuffering()
    sync.onSnapshot(10, breads(7, 5))
    sync.onMessage({ version: 12, changes: [{ breadId: 1, remaining: 3 }] })

    // 예약 뒤 다시 조회한 목록이 버전 11 시점에 읽혔다 (응답보다 메시지가 먼저 도착)
    sync.onSnapshot(11, breads(5, 5))

    expect(sync.remainingOf(1)).toBe(3)
    expect(sync.version).toBe(12)
    expect(sync.onMessage({ version: 12, changes: [{ breadId: 1, remaining: 99 }] })).toBe(false)
  })

  it('목록에 없는 빵의 메시지는 무시한다', () => {
    const sync = new StockSync()
    sync.startBuffering()
    sync.onSnapshot(1, breads(5, 5))

    sync.onMessage({ version: 2, changes: [{ breadId: 99, remaining: 3 }] })

    expect(sync.remainingOf(99)).toBeUndefined()
    expect(sync.version).toBe(2)
  })
})
