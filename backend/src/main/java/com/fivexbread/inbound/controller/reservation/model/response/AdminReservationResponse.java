package com.fivexbread.inbound.controller.reservation.model.response;

import static com.fivexbread.inbound.controller.reservation.model.response.ReservationResponse.toOffset;

import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import com.fivexbread.domain.reservation.model.vo.AdminReservationCard;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fivexbread.common.MaskedName;
import com.fivexbread.common.MaskedPhone;
import com.fivexbread.inbound.controller.reservation.model.response.ReservationResponse.ItemResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 사장님 예약 카드 (13-9, M-08: "1010-0427, 김*건 · 11:00 픽업, 빵 목록, 금액"). 수령 검색·QR 검증·판매일 예약 목록(A-05)이 같이 쓴다.
 * 손님 이름·번호는 마스킹한 값만 담는다 (AU-10). 탈퇴한 손님이면 둘 다 null.
 */
public record AdminReservationResponse(
        long reservationId,
        String reservationNo,
        ReservationStatus status,
        LocalDate saleDate,
        long pickupSlotId,
        OffsetDateTime pickupAt,
        @Schema(nullable = true) MaskedName memberName,
        @Schema(nullable = true) MaskedPhone phone,
        List<ItemResponse> items,
        int totalPrice
) {

    public static AdminReservationResponse from(AdminReservationCard card) {
        return new AdminReservationResponse(card.reservationId(), card.reservationNo(), card.status(), card.saleDate(),
                card.pickupSlotId(), toOffset(card.pickupAt()), card.memberName(), card.phone(),
                ItemResponse.from(card.items()), card.totalPrice());
    }
}
