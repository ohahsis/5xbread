package com.fivexbread.inbound.controller.reservation.model.response;

import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import com.fivexbread.domain.reservation.model.vo.ReservationDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import static com.fivexbread.inbound.controller.reservation.model.response.ReservationResponse.toOffset;

import com.fivexbread.inbound.controller.reservation.model.response.ReservationResponse.ItemResponse;
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
        @Schema(nullable = true) OffsetDateTime canceledAt,
        List<ItemResponse> items,
        int totalPrice
) {

    public static ReservationDetailResponse from(ReservationDetail detail) {
        LocalDateTime canceledAt = detail.canceledAt();
        return new ReservationDetailResponse(detail.reservationId(), detail.reservationNo(), detail.status(),
                detail.saleDate(), toOffset(detail.pickupAt()), toOffset(detail.cancelDeadline()),
                detail.cancelable(), canceledAt == null ? null : toOffset(canceledAt), ItemResponse.from(detail.items()),
                detail.totalPrice());
    }
}
