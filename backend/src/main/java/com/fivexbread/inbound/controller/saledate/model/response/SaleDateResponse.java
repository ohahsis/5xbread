package com.fivexbread.inbound.controller.saledate.model.response;

import com.fivexbread.domain.saledate.model.PickupSlot;
import com.fivexbread.domain.saledate.model.SaleDate;
import static com.fivexbread.config.ClockConfig.KST;

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

    public static SaleDateResponse of(SaleDate saleDate, List<PickupSlot> slots) {
        return new SaleDateResponse(saleDate.getSaleDate(),
                saleDate.getOpenAt().atZone(KST).toOffsetDateTime(),
                saleDate.getCloseAt().atZone(KST).toOffsetDateTime(),
                saleDate.isHoliday(),
                slots.stream().map(slot -> slot.getPickupAt().toLocalTime()).sorted().toList());
    }
}
