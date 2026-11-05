package com.fivexbread.reservation;

import static com.fivexbread.reservation.ReservationResponse.toOffset;

import com.fivexbread.reservation.ReservationResponse.ItemResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/** 내 예약 목록의 한 건이자 상세 (C-05). 시각은 +09:00 오프셋 */
public record ReservationDetailResponse(
        long reservationId,
        String reservationNo,
        ReservationStatus status,
        LocalDate saleDate,
        OffsetDateTime pickupAt,
        OffsetDateTime cancelDeadline,
        boolean cancelable,
        OffsetDateTime canceledAt,
        List<ItemResponse> items,
        int totalPrice
) {

    static ReservationDetailResponse of(Reservation reservation, List<ItemResponse> items, LocalDateTime now) {
        LocalDateTime canceledAt = reservation.getCanceledAt();
        return new ReservationDetailResponse(reservation.getId(), reservation.getReservationNo(), reservation.getStatus(),
                reservation.getSaleDate(), toOffset(reservation.getPickupAt()), toOffset(reservation.cancelDeadline()),
                reservation.cancelable(now), canceledAt == null ? null : toOffset(canceledAt), items,
                reservation.getTotalPrice());
    }
}
