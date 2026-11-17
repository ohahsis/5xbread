package com.fivexbread.member;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 회원 이름 규칙 (9장 필드 규격 "회원"): 한글 완성형·영문 2~20자, 단어 사이 공백 하나만. 가입(C-01)과 이름 수정(C-10)이 같이 쓴다.
 * 앞뒤 공백은 검사 전에 지운 값을 넘긴다 (가입·수정 모두 strip). null 은 통과(@NotBlank·@NotNull 과 함께 사용)
 */
@Documented
@Size(min = 2, max = 20)
@Pattern(regexp = "^[가-힣A-Za-z]+( [가-힣A-Za-z]+)*$")    // 연속 공백 불가. 완성형만이라 UTF-16 길이 = 글자 수
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MemberName {

    String MESSAGE = "이름은 한글 또는 영문 2~20자로 입력해 주세요";

    String message() default MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
