package com.fivexbread.saledate;

import java.time.LocalDateTime;

/** 판매일의 오픈·마감·휴무. 엔티티와 달리 불변이라 로컬 캐시에 둔다 */
public record SaleDateSchedule(LocalDateTime openAt, LocalDateTime closeAt, boolean holiday) {

    /** 예약 기간은 [오픈, 마감) (R-11, decisions.md 6장) */
    public SaleDateStatus status(LocalDateTime now) {
        if (holiday) {
            return SaleDateStatus.HOLIDAY;
        }
        if (now.isBefore(openAt)) {
            return SaleDateStatus.NOT_OPEN;
        }
        return now.isBefore(closeAt) ? SaleDateStatus.OPEN : SaleDateStatus.CLOSED;
    }
}
