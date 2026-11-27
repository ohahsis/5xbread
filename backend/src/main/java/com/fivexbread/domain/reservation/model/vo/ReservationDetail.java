package com.fivexbread.domain.reservation.model.vo;

import com.fivexbread.domain.reservation.model.Reservation;
import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 내 예약 목록의 한 건이자 상세 (C-05). 취소 가능 여부는 조회 시각 기준 */
public record ReservationDetail(long reservationId, String reservationNo, ReservationStatus status, LocalDate saleDate,
                                LocalDateTime pickupAt, LocalDateTime cancelDeadline, boolean cancelable,
                                LocalDateTime canceledAt, List<ReservationLine> items, int totalPrice) {

    public static ReservationDetail of(Reservation reservation, List<ReservationLine> items, LocalDateTime now) {
        return new ReservationDetail(reservation.getId(), reservation.getReservationNo(), reservation.getStatus(),
                reservation.getSaleDate(), reservation.getPickupAt(), reservation.cancelDeadline(),
                reservation.cancelable(now), reservation.getCanceledAt(), items, reservation.getTotalPrice());
    }
}
