package com.fivexbread.domain.reservation.model.enums;

/**
 * 예약의 재고·한도 검사 방식 (decisions.md 6장 "예약 측정 모드").
 * 두 모드 모두 요청 제한·Idempotency·예약번호에는 Redis 를 쓴다. 다른 것은 재고·1인 한도·하루 건수를 어디서 검사하느냐뿐이다.
 */
public enum ReservationMode {

    /** v2(기본·운영): Redis Lua 로 검사·차감한 뒤 DB 조건부 UPDATE 로 확정, 실패하면 Redis 보상 */
    REDIS,

    /**
     * v1(비교 측정용): 재고·한도 검사를 DB 로. Redis 재고·실시간 잔여(C-03)를 바꾸지 않아 목록 잔여가 맞지 않는다.
     * docker·test 프로필에서만 켤 수 있다 (ReservationModeGuard)
     */
    DB_ONLY
}
