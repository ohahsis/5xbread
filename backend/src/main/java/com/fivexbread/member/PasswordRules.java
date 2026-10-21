package com.fivexbread.member;

import java.util.regex.Pattern;

/** 비밀번호 규칙. 가입·비밀번호 변경·임시 비밀번호에서 함께 쓴다 */
public final class PasswordRules {

    public static final String RULE_MESSAGE = "비밀번호는 영문과 숫자를 포함해 8~64자로 입력해 주세요";
    public static final String REPEAT_MESSAGE = "같은 문자를 4번 연속 사용할 수 없습니다";
    public static final String PHONE_MESSAGE = "비밀번호에 휴대폰 번호를 포함할 수 없습니다";

    // 허용 문자: 영문 대소문자, 숫자, !@#$%^&*()-_=+[]{};:,.?/~ (공백·한글 불가)
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9!@#$%^&*()\\-_=+\\[\\]{};:,.?/~]{8,64}$");
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern REPEAT4 = Pattern.compile("(.)\\1{3}");

    private PasswordRules() {
    }

    public static boolean meetsBasicRule(String password) {
        return ALLOWED.matcher(password).matches()
                && LETTER.matcher(password).find()
                && DIGIT.matcher(password).find();
    }

    public static boolean hasRepeatedChars(String password) {
        return REPEAT4.matcher(password).find();
    }

    /** 번호 뒷 8자리(010 다음)가 들어 있는지. 번호가 형식에 맞지 않으면 false(번호 검증이 따로 잡음) */
    public static boolean containsPhone(String password, String phone) {
        if (password == null || !PhoneValidator.isValid(phone)) {
            return false;
        }
        return password.contains(PhoneValidator.normalize(phone).substring(3));
    }
}
