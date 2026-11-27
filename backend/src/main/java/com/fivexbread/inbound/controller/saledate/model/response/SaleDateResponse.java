package com.fivexbread.inbound.controller.saledate.model.response;

import com.fivexbread.domain.saledate.model.vo.SaleDateResult;
import static com.fivexbread.common.Times.KST;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/** 판매일 설정. 시각은 +09:00 오프셋 (decisions.md 3장) */
public record SaleDateResponse(
        LocalDate saleDate,
        OffsetDateTime openAt,
        OffsetDateTime closeAt,
        boolean holiday,
        List<LocalTime> pickupTimes
) {

    public static SaleDateResponse from(SaleDateResult result) {
        return new SaleDateResponse(result.saleDate(),
                result.openAt().atZone(KST).toOffsetDateTime(),
                result.closeAt().atZone(KST).toOffsetDateTime(),
                result.holiday(),
                result.pickupTimes());
    }
}
