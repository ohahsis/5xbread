package com.fivexbread.domain.reservation.model.vo;

import com.fivexbread.common.MaskedName;
import com.fivexbread.common.MaskedPhone;
import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 사장님 예약 카드 (13-9, M-08). 이름·번호는 마스킹한 값만 (AU-10). 탈퇴한 손님이면 둘 다 null */
public record AdminReservationCard(long reservationId, String reservationNo, ReservationStatus status,
                                   LocalDate saleDate, long pickupSlotId, LocalDateTime pickupAt,
                                   MaskedName memberName, MaskedPhone phone, List<ReservationLine> items,
                                   int totalPrice) {
}
