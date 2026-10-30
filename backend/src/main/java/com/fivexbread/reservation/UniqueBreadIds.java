package com.fivexbread.reservation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 예약 항목에 같은 빵이 두 번 나오면 안 됨 (13-1 1단계). 수량은 한 항목에 합쳐 보낸다 */
@Documented
@Constraint(validatedBy = UniqueBreadIdsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueBreadIds {

    String message() default "같은 빵을 두 번 담을 수 없습니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
