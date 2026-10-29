package com.fivexbread.saledate;

import static com.fivexbread.common.ClockConfig.KST;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** 손님용 판매일 목록·상세 (C-02, U-04 날짜 탭·"10:00 오픈" 안내). 시각은 +09:00 오프셋 */
public record SaleDateStatusResponse(
        LocalDate saleDate,
        SaleDateStatus status,
        OffsetDateTime openAt,
        OffsetDateTime closeAt
) {

    static SaleDateStatusResponse of(SaleDate saleDate, LocalDateTime now) {
        return new SaleDateStatusResponse(saleDate.getSaleDate(), saleDate.status(now),
                saleDate.getOpenAt().atZone(KST).toOffsetDateTime(),
                saleDate.getCloseAt().atZone(KST).toOffsetDateTime());
    }
}
