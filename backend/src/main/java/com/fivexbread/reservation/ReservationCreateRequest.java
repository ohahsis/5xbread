package com.fivexbread.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** 예약 생성 (13-1 1단계 형식 검증, 10장 예시). 기간·한도·소속 같은 업무 규칙은 서비스가 본다 */
public record ReservationCreateRequest(
        @NotNull LocalDate saleDate,
        @NotNull Long pickupSlotId,
        @NotNull @Size(min = 1, max = 30) @UniqueBreadIds List<@NotNull @Valid Item> items
) {

    public record Item(@NotNull Long breadId, @Positive int quantity) {
    }
}
