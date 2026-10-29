package com.fivexbread.saledate;

/** 손님 빵 목록의 빵별 상태 (13-4 6단계). 매진은 예약 기간(OPEN)에만 따로 표시한다 */
public enum SaleBreadStatus {
    AVAILABLE,
    SOLD_OUT,
    NOT_OPEN,
    CLOSED;

    static SaleBreadStatus of(SaleDateStatus day, int remaining) {
        return switch (day) {
            case NOT_OPEN -> NOT_OPEN;
            case OPEN -> remaining > 0 ? AVAILABLE : SOLD_OUT;
            case CLOSED, HOLIDAY -> CLOSED;
        };
    }
}
