package com.fivexbread.saledate;

import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * PUT /api/v1/admin/sale-dates/{date} (A-04). 없으면 만들고 있으면 통째로 바꾼다.
 * 형식(값 누락)만 여기서 400 으로 거르고, 시각·시간대 규칙은 SaleDateRules 가 한 번에 모아 422 로 돌려준다.
 *
 * @param pickupTimes 픽업 시각(HH:mm) 목록. 휴무일이면 빈 목록
 */
public record SaleDateRequest(
        @NotNull OffsetDateTime openAt,
        @NotNull OffsetDateTime closeAt,
        @NotNull Boolean holiday,
        @NotNull List<@NotNull LocalTime> pickupTimes
) {
}
