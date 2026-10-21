package com.fivexbread.member;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 휴대폰 번호 (AU-01, 9장 필드 규격). 하이픈·공백을 뺀 뒤 ^010\d{8}$. null은 통과(@NotBlank와 함께 사용) */
@Documented
@Constraint(validatedBy = PhoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Phone {

    String message() default "올바른 휴대폰 번호를 입력해 주세요 (010으로 시작하는 11자리)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
