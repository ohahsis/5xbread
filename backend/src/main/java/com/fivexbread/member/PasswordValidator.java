package com.fivexbread.member;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordValidator implements ConstraintValidator<Password, String> {   // @Password 가 붙은 String 값을 검사한다는 뜻

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;    // null 을 통과시키는 이유: "값이 비었는지"와 "형식이 맞는지"는 다른 검사라서 역할을 나눈 것
        }
        if (!PasswordRules.meetsBasicRule(value)) {
            return false;                                  // 기본 문구(RULE_MESSAGE)
        }
        if (PasswordRules.hasRepeatedChars(value)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(PasswordRules.REPEAT_MESSAGE).addConstraintViolation();
            return false;
        }
        return true;
    }
}
