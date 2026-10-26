package com.fivexbread.saledate;

import static com.fivexbread.common.ClockConfig.KST;

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

    static SaleDateResponse of(SaleDate saleDate, List<PickupSlot> slots) {
        return new SaleDateResponse(saleDate.getSaleDate(),
                saleDate.getOpenAt().atZone(KST).toOffsetDateTime(),
                saleDate.getCloseAt().atZone(KST).toOffsetDateTime(),
                saleDate.isHoliday(),
                slots.stream().map(slot -> slot.getPickupAt().toLocalTime()).sorted().toList());
    }
}
