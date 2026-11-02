package com.fivexbread.reservation;

/** 예약 상태 (3장). 취소(CANCELED)만 잔여·1인 누적·하루 건수에서 빠진다 */
public enum ReservationStatus {
    RESERVED,
    CANCELED,
    PICKED_UP,
    NO_SHOW
}
