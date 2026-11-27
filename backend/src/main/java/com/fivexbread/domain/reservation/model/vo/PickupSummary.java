package com.fivexbread.domain.reservation.model.vo;

import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 픽업 준비 합계 (A-05). 시간대는 픽업 시각 순, 빵은 진열 순서 → id 순 */
public record PickupSummary(LocalDate saleDate, ReservationStatus status, List<Slot> slots, List<Bread> totals) {

    /** @param reservationCount 이 시간대에 포장할 예약(봉투) 수 */
    public record Slot(long pickupSlotId, LocalDateTime pickupAt, int reservationCount, List<Bread> breads) {
    }

    public record Bread(long breadId, String name, int quantity) {
    }
}
