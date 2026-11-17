// C-03 실시간 잔여: 목록(스냅샷)과 STOMP 메시지를 버전으로 맞추는 규칙. React·STOMP 와 무관한 순수 클래스.
//
// 규칙 (decisions.md 6장 "C-03 스냅샷"):
//   구독 먼저 → 들어오는 메시지는 버퍼에 쌓기 → 목록 조회 → 목록 version 보다 큰 메시지만 적용
//   재연결하면 다시 버퍼링부터 (목록을 다시 받아야 끊긴 동안의 변경을 알 수 있다)
//
// 버전은 판매일마다 하나씩 오르는 수(Redis stockver:{date})이고, 메시지의 remaining 은 차이가 아니라 절대값이다.
// 그래서 "이미 반영한 버전 이하"는 버리기만 하면 되고, 하나를 놓쳐도 다음 메시지가 맞춰 준다.

export type StockChange = { breadId: number; remaining: number }

/** 서버 StockMessage. 200ms 동안 바뀐 빵만 담고, version 은 그 안의 가장 큰 버전 */
export type StockMessage = { version: number; changes: StockChange[] }

export class StockSync {
  /** 반영한 가장 큰 버전. null 이면 아직 목록이 없어 버퍼링 중 */
  private appliedVersion: number | null = null
  private buffer: StockMessage[] = []
  private remaining = new Map<number, number>()

  /** 구독 직전에 부른다 (처음 연결·재연결 모두). 화면의 잔여 값은 지우지 않는다 */
  startBuffering(): void {
    this.appliedVersion = null
    this.buffer = []
  }

  /** 메시지 하나. 화면에 보이는 잔여가 바뀌었으면 true */
  onMessage(message: StockMessage): boolean {
    if (this.appliedVersion === null) {
      this.buffer.push(message)
      return false
    }
    return this.apply(message)
  }

  /**
   * 목록(스냅샷)을 받았을 때. 버퍼에 쌓인 메시지 중 목록보다 새로운 것만 버전 순으로 적용한다.
   *
   * 이미 실시간으로 반영 중인데(예약 성공 뒤 목록 다시 조회 등) 목록이 지금 화면보다 오래된 버전이면
   * 잔여는 바꾸지 않는다. 목록 요청을 보낸 뒤 응답이 오기 전에 더 새로운 메시지가 먼저 반영된 경우다.
   * 이때 목록 값으로 덮으면 그 메시지가 지워지고, 버전도 되돌아가 다음 메시지까지 틀린 값이 보인다.
   */
  onSnapshot(version: number, breads: StockChange[]): void {
    const live = this.appliedVersion !== null
    if (live && version < (this.appliedVersion as number)) {
      for (const bread of breads) {
        if (!this.remaining.has(bread.breadId)) this.remaining.set(bread.breadId, bread.remaining)
      }
      return
    }

    this.remaining = new Map(breads.map((bread) => [bread.breadId, bread.remaining]))
    this.appliedVersion = version

    const buffered = [...this.buffer].sort((a, b) => a.version - b.version)
    this.buffer = []
    for (const message of buffered) {
      this.apply(message)
    }
  }

  /** 빵의 현재 잔여. 목록에 없던 빵이면 undefined */
  remainingOf(breadId: number): number | undefined {
    return this.remaining.get(breadId)
  }

  /** 반영한 버전 (화면 표시·테스트용). 버퍼링 중이면 null */
  get version(): number | null {
    return this.appliedVersion
  }

  private apply(message: StockMessage): boolean {
    if (this.appliedVersion === null || message.version <= this.appliedVersion) {
      return false
    }
    for (const change of message.changes) {
      if (this.remaining.has(change.breadId)) {      // 목록에 없는 빵(판매 중이 아님)은 화면에 없으니 무시
        this.remaining.set(change.breadId, change.remaining)
      }
    }
    this.appliedVersion = message.version
    return true
  }
}
