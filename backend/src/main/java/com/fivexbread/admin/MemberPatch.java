package com.fivexbread.admin;

import com.fivexbread.common.ApiExceptionHandler.FieldViolation;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 회원 PATCH 본문 (JSON Merge Patch, 10장). 지금은 {"reservationBlockedUntil": null} 해제만 받는다 (A-10).
 * 사장님이 제한 날짜를 정하는 기능은 없으므로 값을 보내면 무시하지 않고 400. 위반은 모아서 errors[].
 */
final class MemberPatch {

    private static final String BLOCKED_UNTIL = "reservationBlockedUntil";

    private MemberPatch() {
    }

    static void requireBlockClear(Map<String, Object> body) {
        List<FieldViolation> violations = new ArrayList<>();
        body.keySet().stream().filter(field -> !BLOCKED_UNTIL.equals(field)).sorted()
                .forEach(field -> violations.add(new FieldViolation(field, "Unknown", "바꿀 수 없는 필드입니다")));
        if (!body.containsKey(BLOCKED_UNTIL)) {
            violations.add(new FieldViolation(BLOCKED_UNTIL, "Required", "해제하려면 null 을 보내 주세요"));
        } else if (body.get(BLOCKED_UNTIL) != null) {
            violations.add(new FieldViolation(BLOCKED_UNTIL, "NotAllowed", "제한은 해제만 할 수 있습니다"));
        }
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, null, Map.of("errors", violations));
        }
    }
}
