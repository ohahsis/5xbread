package com.fivexbread.member;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class PhoneValidator implements ConstraintValidator<Phone, String> {

    private static final Pattern PHONE = Pattern.compile("^010\\d{8}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || isValid(value);
    }

    public static boolean isValid(String phone) {
        return phone != null && PHONE.matcher(normalize(phone)).matches();
    }

    /** 010-1234-5678, 010 1234 5678 → 01012345678 */
    public static String normalize(String phone) {
        return phone == null ? null : phone.replaceAll("[-\\s]", "");
    }
}
