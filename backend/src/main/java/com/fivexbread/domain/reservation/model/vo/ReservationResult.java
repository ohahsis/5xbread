package com.fivexbread.domain.reservation.model.vo;

import com.fivexbread.domain.reservation.model.Reservation;
import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 예약 (생성·취소·수령의 결과). 트랜잭션 안에서 만든다 */
public record ReservationResult(long reservationId, String reservationNo, ReservationStatus status, LocalDate saleDate,
                                LocalDateTime pickupAt, LocalDateTime cancelDeadline, LocalDateTime canceledAt,
                                LocalDateTime pickedUpAt, List<ReservationLine> items, int totalPrice) {

    public static ReservationResult of(Reservation reservation, List<ReservationLine> items) {
        return of(reservation, reservation.getStatus(), reservation.getCanceledAt(), reservation.getPickedUpAt(), items);
    }

    /** 조건부 UPDATE 로 취소한 직후. 읽어 둔 엔티티는 옛 상태라 바뀐 값을 따로 받는다 */
    public static ReservationResult canceled(Reservation reservation, LocalDateTime canceledAt, List<ReservationLine> items) {
        return of(reservation, ReservationStatus.CANCELED, canceledAt, null, items);
    }

    /** 조건부 UPDATE 로 수령 처리한 직후 */
    public static ReservationResult pickedUp(Reservation reservation, LocalDateTime pickedUpAt, List<ReservationLine> items) {
        return of(reservation, ReservationStatus.PICKED_UP, null, pickedUpAt, items);
    }

    private static ReservationResult of(Reservation reservation, ReservationStatus status, LocalDateTime canceledAt,
                                        LocalDateTime pickedUpAt, List<ReservationLine> items) {
        return new ReservationResult(reservation.getId(), reservation.getReservationNo(), status,
                reservation.getSaleDate(), reservation.getPickupAt(), reservation.cancelDeadline(), canceledAt,
                pickedUpAt, items, reservation.getTotalPrice());
    }
}
