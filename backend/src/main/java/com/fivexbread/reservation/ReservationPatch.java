package com.fivexbread.reservation;

import com.fivexbread.common.ApiExceptionHandler.FieldViolation;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 예약 PATCH 본문 (JSON Merge Patch, RFC 7396, 10장 "상태 변경"). 서버가 허용한 전이만 받는다 (3장 상태 흐름).
 * 모르는 필드를 조용히 무시하지 않으려고 본문을 Map 으로 받아 직접 검사한다. 위반은 모아서 400 errors[].
 *
 * @param cancelReason 직권 취소 사유(A-08). 손님 취소면 null
 */
record ReservationPatch(ReservationStatus status, String cancelReason) {

    static final int CANCEL_REASON_MIN = 5;                 // 9장 필드 규격: 직권 취소 사유 5~100자
    static final int CANCEL_REASON_MAX = 100;

    private static final String STATUS = "status";
    private static final String CANCEL_REASON = "cancelReason";

    // 손님은 취소만 (13-2 1단계)
    private static final Set<ReservationStatus> CUSTOMER_STATUSES = Set.of(ReservationStatus.CANCELED);
    // 사장님: 직권 취소(A-08). A-06 수령에서 PICKED_UP 을 더한다
    private static final Set<ReservationStatus> ADMIN_STATUSES = Set.of(ReservationStatus.CANCELED);

    static ReservationPatch customer(Map<String, Object> body) {
        List<FieldViolation> violations = new ArrayList<>();
        unknownFields(body, Set.of(STATUS), violations);
        ReservationStatus status = status(body, CUSTOMER_STATUSES, violations);
        throwIfAny(violations);
        return new ReservationPatch(status, null);
    }

    static ReservationPatch admin(Map<String, Object> body) {
        List<FieldViolation> violations = new ArrayList<>();
        unknownFields(body, Set.of(STATUS, CANCEL_REASON), violations);
        ReservationStatus status = status(body, ADMIN_STATUSES, violations);
        String reason = null;
        if (status == ReservationStatus.CANCELED) {
            reason = cancelReason(body.get(CANCEL_REASON), violations);
        }
        throwIfAny(violations);
        return new ReservationPatch(status, reason);
    }

    private static void unknownFields(Map<String, Object> body, Set<String> allowed, List<FieldViolation> violations) {
        body.keySet().stream().filter(field -> !allowed.contains(field)).sorted()
                .forEach(field -> violations.add(new FieldViolation(field, "Unknown", "바꿀 수 없는 필드입니다")));
    }

    private static ReservationStatus status(Map<String, Object> body, Set<ReservationStatus> allowed,
                                            List<FieldViolation> violations) {
        Object value = body.get(STATUS);
        if (value == null) {
            violations.add(new FieldViolation(STATUS, "NotNull", "status 는 필수입니다"));
            return null;
        }
        ReservationStatus status = allowed.stream().filter(s -> s.name().equals(value)).findFirst().orElse(null);
        if (status == null) {
            violations.add(new FieldViolation(STATUS, "NotAllowed", "바꿀 수 없는 상태입니다"));
        }
        return status;
    }

    // 앞뒤 공백을 지운 뒤 글자(코드 포인트) 수로 센다. 줄바꿈은 허용 (9장: 줄바꿈은 설명·사유에만)
    private static String cancelReason(Object value, List<FieldViolation> violations) {
        String message = "취소 사유를 " + CANCEL_REASON_MIN + "자 이상 " + CANCEL_REASON_MAX + "자 이하로 입력해 주세요";
        if (!(value instanceof String text)) {
            violations.add(new FieldViolation(CANCEL_REASON, "NotNull", message));
            return null;
        }
        String reason = text.strip();
        int length = reason.codePointCount(0, reason.length());
        if (length < CANCEL_REASON_MIN || length > CANCEL_REASON_MAX) {
            violations.add(new FieldViolation(CANCEL_REASON, "Size", message));
        }
        return reason;
    }

    private static void throwIfAny(List<FieldViolation> violations) {
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, null, Map.of("errors", violations));
        }
    }
}
