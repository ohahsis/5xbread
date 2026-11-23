package com.fivexbread.inbound.controller.reservation.model.request;

import com.fivexbread.inbound.controller.reservation.model.request.ReservationPatch;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * 예약 PATCH 본문의 OpenAPI 문서용 모양. 실제로는 모르는 필드를 400 으로 거르려고 Map 으로 받아 ReservationPatch 가 검사한다.
 * 문서에 본문 모양이 없으면 프론트가 타입을 만들 수 없어 둔다.
 */
public final class ReservationPatchBodies {

    private ReservationPatchBodies() {
    }

    @Schema(name = "ReservationCancelPatch", description = "손님 취소 (C-06). 이 필드만 받는다")
    public record CustomerCancel(@Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "CANCELED") String status) {
    }

    @Schema(name = "AdminReservationPatch", description = "직권 취소(A-08) 또는 수령(A-06). 이 필드만 받는다")
    public record AdminStatusChange(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"CANCELED", "PICKED_UP"}) String status,
            @Schema(description = "CANCELED 일 때 필수, 앞뒤 공백을 뺀 5~100자. PICKED_UP 이면 보내지 않는다",
                    minLength = ReservationPatch.CANCEL_REASON_MIN, maxLength = ReservationPatch.CANCEL_REASON_MAX)
            String cancelReason) {
    }
}
