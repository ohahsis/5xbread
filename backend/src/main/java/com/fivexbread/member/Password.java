package com.fivexbread.member;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 비밀번호 단일 필드 규칙 (AU-03, 9장 필드 규격): 8~64자, 영문·숫자 각 1자 이상, 허용 문자만, 같은 문자 4연속 불가.
 * 휴대폰 번호 포함 여부는 두 필드를 함께 봐야 해서 {@link PasswordExcludesPhone}이 맡는다.
 * null 은 통과(@NotBlank 와 함께 사용)
 */
@Documented // Javadoc 을 만들 때 이 어노테이션도 문서에 표시되도록 한다.
@Constraint(validatedBy = PasswordValidator.class)  // 검증용 메타 어노테이션. 실제 검사를 어떤 클래스가 하는지 지정한다.
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})   // 해당 어노테이션을 붙일 수 있는 지점을 제한한다.
@Retention(RetentionPolicy.RUNTIME) // 해당 어노테이션 정보를 언제까지 유지할지 지정한다. 여기서는 실행 중에도 남아 있어서 Validator 가 리플렉션으로 찾을 수 있도록 한다.
public @interface Password {

    String message() default PasswordRules.RULE_MESSAGE;

    Class<?>[] groups() default {}; // groups 는 상황별로 검증을 나눌 때 사용한다. 기본값 {} 는 "항상 검사"라는 뜻

    Class<? extends Payload>[] payload() default {};
}
