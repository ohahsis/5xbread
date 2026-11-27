package com.fivexbread.inbound.controller.reservation.model.response;

import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import com.fivexbread.domain.reservation.model.vo.ReservationLine;
import com.fivexbread.domain.reservation.model.vo.ReservationResult;
import io.swagger.v3.oas.annotations.media.Schema;
import static com.fivexbread.common.Times.KST;

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
        @Schema(nullable = true) OffsetDateTime canceledAt,
        @Schema(nullable = true) OffsetDateTime pickedUpAt,
        List<ItemResponse> items,
        int totalPrice
) {

    /** @param unitPrice 예약 당시 가격 (reservation_item.unit_price) */
    public record ItemResponse(long breadId, String name, int quantity, int unitPrice) {

        public static ItemResponse from(ReservationLine line) {
            return new ItemResponse(line.breadId(), line.name(), line.quantity(), line.unitPrice());
        }

        public static List<ItemResponse> from(List<ReservationLine> lines) {
            return lines.stream().map(ItemResponse::from).toList();
        }
    }

    public static ReservationResponse from(ReservationResult result) {
        return new ReservationResponse(result.reservationId(), result.reservationNo(), result.status(),
                result.saleDate(), toOffset(result.pickupAt()), toOffset(result.cancelDeadline()),
                toOffset(result.canceledAt()), toOffset(result.pickedUpAt()), ItemResponse.from(result.items()),
                result.totalPrice());
    }

    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
