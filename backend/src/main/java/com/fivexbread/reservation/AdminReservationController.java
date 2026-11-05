package com.fivexbread.reservation;

import com.fivexbread.auth.AuthMember;
import com.fivexbread.common.ApiExceptionHandler.FieldViolation;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사장님 예약 검색·상태 변경 (10장 관리 API). 직권 취소(A-08)와 수령(A-06)이 같은 PATCH 경로를 쓴다 */
@RestController
@RequestMapping("/api/v1/admin/reservations")
@RequiredArgsConstructor
public class AdminReservationController {

    private static final Pattern RESERVATION_NO = Pattern.compile("^\\d{4}-\\d{4}$");   // MMDD-NNNN
    private static final Pattern PHONE_LAST4 = Pattern.compile("^\\d{4}$");

    private final ReservationCancelFacade reservationCancelFacade;
    private final ReservationPickupService reservationPickupService;
    private final AdminReservationQueryService queryService;
    private final Clock clock;

    /**
     * 수령용 검색 (A-06, 13-9 2단계). 예약번호와 뒷 4자리 중 정확히 하나. 판매일을 비우면 오늘.
     * 둘 다 없으면 판매일 전체 목록이 되는데, 그것은 픽업 준비 목록(A-05, 2차)이라 400
     */
    @GetMapping
    public List<AdminReservationResponse> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate saleDate,
            @RequestParam(required = false) String reservationNo,
            @RequestParam(required = false) String phoneLast4,
            @RequestParam(required = false) ReservationStatus status) {
        String no = blankToNull(reservationNo);
        String last4 = blankToNull(phoneLast4);
        if ((no == null) == (last4 == null)) {
            throw invalid("reservationNo", "OneRequired", "예약번호와 번호 뒷 4자리 중 하나만 입력해 주세요");
        }
        if (no != null && !RESERVATION_NO.matcher(no).matches()) {
            throw invalid("reservationNo", "Pattern", "예약번호는 1010-0427 형식입니다");
        }
        if (last4 != null && !PHONE_LAST4.matcher(last4).matches()) {
            throw invalid("phoneLast4", "Pattern", "번호 뒷 4자리는 숫자 4자리입니다");
        }
        return queryService.search(saleDate == null ? LocalDate.now(clock) : saleDate, no, last4, status);
    }

    @PatchMapping(value = "/{reservationId}", consumes = ReservationController.MERGE_PATCH_JSON)
    public ReservationResponse patch(@AuthenticationPrincipal AuthMember admin, @PathVariable long reservationId,
                                     @RequestBody Map<String, Object> body) {
        ReservationPatch patch = ReservationPatch.admin(body);
        return switch (patch.status()) {
            case CANCELED -> reservationCancelFacade.cancel(reservationId, ReservationActor.admin(admin.memberId()),
                    patch.cancelReason());
            case PICKED_UP -> reservationPickupService.pickUp(reservationId, admin.memberId());
            default -> throw new IllegalStateException("ReservationPatch 가 허용하지 않은 상태 " + patch.status());
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static BusinessException invalid(String field, String code, String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, null,
                Map.of("errors", List.of(new FieldViolation(field, code, message))));
    }
}
