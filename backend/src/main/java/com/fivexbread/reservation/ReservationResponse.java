package com.fivexbread.reservation;

import static com.fivexbread.common.ClockConfig.KST;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 예약 (10장 예시). 시각은 +09:00 오프셋 */
public record ReservationResponse(
        long reservationId,
        String reservationNo,
        ReservationStatus status,
        LocalDate saleDate,
        OffsetDateTime pickupAt,
        OffsetDateTime cancelDeadline,
        List<ItemResponse> items,
        int totalPrice
) {

    /** @param unitPrice 예약 당시 가격 (reservation_item.unit_price) */
    public record ItemResponse(long breadId, String name, int quantity, int unitPrice) {
    }

    static ReservationResponse of(Reservation reservation, List<ItemResponse> items) {
        return new ReservationResponse(reservation.getId(), reservation.getReservationNo(), reservation.getStatus(),
                reservation.getSaleDate(), reservation.getPickupAt().atZone(KST).toOffsetDateTime(),
                reservation.cancelDeadline().atZone(KST).toOffsetDateTime(), items, reservation.getTotalPrice());
    }
}
