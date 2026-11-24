package com.fivexbread.inbound.controller.reservation.model.response;

import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import com.fivexbread.common.MaskedName;
import com.fivexbread.common.MaskedPhone;
import com.fivexbread.domain.member.model.enums.MemberStatus;
import com.fivexbread.inbound.controller.reservation.model.response.ReservationResponse.ItemResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 사장님 예약 상세. 손님 상세(ReservationDetailResponse)의 내용 + 취소 사유·수령 시각·접수 시각 + 회원(마스킹) + 상태 이력.
 * 시각은 +09:00 오프셋.
 */
public record AdminReservationDetailResponse(
        long reservationId,
        String reservationNo,
        ReservationStatus status,
        LocalDate saleDate,
        long pickupSlotId,
        OffsetDateTime pickupAt,
        OffsetDateTime cancelDeadline,
        @Schema(description = "손님 기준 취소 가능 여부(픽업 60분 전까지). 사장님 직권 취소에는 마감이 없다")
        boolean cancelable,
        @Schema(nullable = true) OffsetDateTime canceledAt,
        @Schema(nullable = true) String cancelReason,
        @Schema(nullable = true) OffsetDateTime pickedUpAt,
        OffsetDateTime createdAt,
        List<ItemResponse> items,
        int totalPrice,
        MemberSummary member,
        List<HistoryResponse> history
) {

    /** 이름·번호는 마스킹한 값 (AU-10). 탈퇴한 회원이면 둘 다 null */
    public record MemberSummary(long memberId, @Schema(nullable = true) MaskedName name, @Schema(nullable = true) MaskedPhone phone,
                                MemberStatus status) {
    }

    /** reservation_history 한 줄. 시간순 */
    public record HistoryResponse(@Schema(nullable = true) ReservationStatus fromStatus, ReservationStatus toStatus, ActorType actorType,
                                  OffsetDateTime changedAt) {
    }

    /** 상태를 바꾼 주체. 사장님 id·이름은 내보내지 않는다. SYSTEM 은 노쇼 배치 */
    public enum ActorType {
        CUSTOMER,
        ADMIN,
        SYSTEM
    }
}
