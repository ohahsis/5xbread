package com.fivexbread.member;

import com.fivexbread.common.ApiExceptionHandler.FieldViolation;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 내 정보 PATCH 본문 (JSON Merge Patch, C-10). 지금 바꿀 수 있는 것은 이름뿐이다.
 * name 은 NOT NULL 컬럼이라 null(삭제)과 빠짐은 400. 모르는 필드는 무시하지 않고 400. 위반은 모아서 errors[].
 * 이름 규칙은 가입과 같은 @MemberName 을 검사기로 돌린다 (앞뒤 공백은 지운 뒤).
 */
final class MyInfoPatch {

    private static final String NAME = "name";

    private MyInfoPatch() {
    }

    /** @return 앞뒤 공백을 지운 새 이름 */
    static String name(Map<String, Object> body, Validator validator) {
        List<FieldViolation> violations = new ArrayList<>();
        body.keySet().stream().filter(field -> !NAME.equals(field)).sorted()
                .forEach(field -> violations.add(new FieldViolation(field, "Unknown", "바꿀 수 없는 필드입니다")));
        Object value = body.get(NAME);
        String name = null;
        if (value == null) {
            violations.add(new FieldViolation(NAME, "Required", MemberName.MESSAGE));
        } else if (!(value instanceof String text)) {
            violations.add(new FieldViolation(NAME, "Type", MemberName.MESSAGE));
        } else {
            name = text.strip();
            for (ConstraintViolation<Name> violation : validator.validate(new Name(name))) {
                violations.add(new FieldViolation(NAME,
                        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                        violation.getMessage()));
            }
        }
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, null, Map.of("errors", violations));
        }
        return name;
    }

    private record Name(@MemberName String name) {
    }
}
