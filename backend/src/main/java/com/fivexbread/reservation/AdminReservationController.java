package com.fivexbread.reservation;

import com.fivexbread.auth.AuthMember;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사장님 예약 상태 변경 (10장 관리 API). 지금은 직권 취소(A-08)만, 수령(A-06)은 status 분기를 하나 더한다 */
@RestController
@RequestMapping("/api/v1/admin/reservations")
@RequiredArgsConstructor
public class AdminReservationController {

    private final ReservationCancelFacade reservationCancelFacade;

    @PatchMapping(value = "/{reservationId}", consumes = ReservationController.MERGE_PATCH_JSON)
    public ReservationResponse patch(@AuthenticationPrincipal AuthMember admin, @PathVariable long reservationId,
                                     @RequestBody Map<String, Object> body) {
        ReservationPatch patch = ReservationPatch.admin(body);
        ReservationActor actor = ReservationActor.admin(admin.memberId());
        return switch (patch.status()) {
            case CANCELED -> reservationCancelFacade.cancel(reservationId, actor, patch.cancelReason());
            default -> throw new IllegalStateException("ReservationPatch 가 허용하지 않은 상태 " + patch.status());
        };
    }
}
