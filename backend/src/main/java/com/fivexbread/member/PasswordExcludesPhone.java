package com.fivexbread.member;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 비밀번호에 휴대폰 번호 뒷 8자리 포함 불가 (9장 필드 규격). 위반은 password 필드 오류로 보고 */
@Documented
@Constraint(validatedBy = PasswordExcludesPhoneValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordExcludesPhone {

    String message() default PasswordRules.PHONE_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
