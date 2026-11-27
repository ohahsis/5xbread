package com.fivexbread.inbound.controller.saledate.model.response;

import com.fivexbread.domain.saledate.model.enums.SaleDateStatus;
import com.fivexbread.domain.saledate.model.vo.SaleDateStatusResult;
import static com.fivexbread.common.Times.KST;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 손님용 판매일 목록·상세 (C-02, U-04 날짜 탭·"10:00 오픈" 안내). 시각은 +09:00 오프셋 */
public record SaleDateStatusResponse(
        LocalDate saleDate,
        SaleDateStatus status,
        OffsetDateTime openAt,
        OffsetDateTime closeAt
) {

    public static SaleDateStatusResponse from(SaleDateStatusResult result) {
        return new SaleDateStatusResponse(result.saleDate(), result.status(),
                result.openAt().atZone(KST).toOffsetDateTime(),
                result.closeAt().atZone(KST).toOffsetDateTime());
    }
}
