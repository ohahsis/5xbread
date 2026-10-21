package com.fivexbread.member;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordExcludesPhoneValidator implements ConstraintValidator<PasswordExcludesPhone, SignupRequest> {

    @Override
    public boolean isValid(SignupRequest request, ConstraintValidatorContext context) {
        if (request == null || !PasswordRules.containsPhone(request.password(), request.phone())) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("password")
                .addConstraintViolation();
        return false;
    }
}
