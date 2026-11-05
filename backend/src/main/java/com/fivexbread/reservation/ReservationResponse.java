package com.fivexbread.reservation;

import static com.fivexbread.common.ClockConfig.KST;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
        OffsetDateTime canceledAt,
        OffsetDateTime pickedUpAt,
        List<ItemResponse> items,
        int totalPrice
) {

    /** @param unitPrice 예약 당시 가격 (reservation_item.unit_price) */
    public record ItemResponse(long breadId, String name, int quantity, int unitPrice) {
    }

    static ReservationResponse of(Reservation reservation, List<ItemResponse> items) {
        return of(reservation, reservation.getStatus(), reservation.getCanceledAt(), reservation.getPickedUpAt(), items);
    }

    /** 조건부 UPDATE 로 취소한 직후. 읽어 둔 엔티티는 옛 상태라 바뀐 값을 따로 받는다 */
    static ReservationResponse canceled(Reservation reservation, LocalDateTime canceledAt, List<ItemResponse> items) {
        return of(reservation, ReservationStatus.CANCELED, canceledAt, null, items);
    }

    /** 조건부 UPDATE 로 수령 처리한 직후 */
    static ReservationResponse pickedUp(Reservation reservation, LocalDateTime pickedUpAt, List<ItemResponse> items) {
        return of(reservation, ReservationStatus.PICKED_UP, null, pickedUpAt, items);
    }

    private static ReservationResponse of(Reservation reservation, ReservationStatus status, LocalDateTime canceledAt,
                                          LocalDateTime pickedUpAt, List<ItemResponse> items) {
        return new ReservationResponse(reservation.getId(), reservation.getReservationNo(), status,
                reservation.getSaleDate(), toOffset(reservation.getPickupAt()), toOffset(reservation.cancelDeadline()),
                toOffset(canceledAt), toOffset(pickedUpAt), items, reservation.getTotalPrice());
    }

    static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
