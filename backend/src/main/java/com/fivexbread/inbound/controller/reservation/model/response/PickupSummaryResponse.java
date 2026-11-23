package com.fivexbread.inbound.controller.reservation.model.response;

import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 픽업 준비 합계 (A-05, M-07 "시간대별 빵 합계도 표시(포장용)"). 예약 목록과 따로 둔다: 목록은 커서로 끊어 읽고 합계는 하루 전체다.
 * 시간대는 픽업 시각 순, 빵은 진열 순서(display_order) → id 순. 해당 상태의 예약이 없는 시간대·빵은 넣지 않는다.
 */
public record PickupSummaryResponse(LocalDate saleDate, ReservationStatus status, List<SlotSummary> slots,
                                    List<BreadQuantityResponse> totals) {

    /** @param reservationCount 이 시간대에 포장할 예약(봉투) 수 */
    public record SlotSummary(long pickupSlotId, OffsetDateTime pickupAt, int reservationCount,
                              List<BreadQuantityResponse> breads) {
    }

    public record BreadQuantityResponse(long breadId, String name, int quantity) {
    }
}
