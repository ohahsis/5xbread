package com.fivexbread.reservation;

import com.fivexbread.common.MaskedName;
import com.fivexbread.common.MaskedPhone;
import com.fivexbread.reservation.ReservationResponse.ItemResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 사장님 수령 화면의 예약 카드 (13-9, M-08: "1010-0427, 김*건 · 11:00 픽업, 빵 목록, 금액"). 검색과 QR 검증이 같이 쓴다.
 * 손님 이름·번호는 마스킹한 값만 담는다 (AU-10). 탈퇴한 손님이면 둘 다 null.
 */
public record AdminReservationResponse(
        long reservationId,
        String reservationNo,
        ReservationStatus status,
        LocalDate saleDate,
        OffsetDateTime pickupAt,
        MaskedName memberName,
        MaskedPhone phone,
        List<ItemResponse> items,
        int totalPrice
) {
}
