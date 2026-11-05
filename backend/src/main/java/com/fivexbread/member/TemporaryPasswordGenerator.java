package com.fivexbread.member;

import java.security.SecureRandom;

/**
 * 임시 비밀번호 (AU-06, 9장 필드 규격, 부록 B): SecureRandom 으로 영문 대소문자 + 숫자 12자.
 * 무작위로만 뽑으면 약 12%는 숫자가 없어 비밀번호 규칙(AU-03)에 어긋난다. 규칙에 맞을 때까지 통째로 다시 뽑아 분포를 치우치게 하지 않는다.
 */
public final class TemporaryPasswordGenerator {

    static final int LENGTH = 12;
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();     // 스레드 안전. 호출마다 만들지 않는다

    private TemporaryPasswordGenerator() {
    }

    public static String generate(String phone) {
        String candidate;
        do {
            char[] chars = new char[LENGTH];
            for (int i = 0; i < LENGTH; i++) {
                chars[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
            }
            candidate = new String(chars);
        } while (!PasswordRules.meetsBasicRule(candidate)
                || PasswordRules.hasRepeatedChars(candidate)
                || PasswordRules.containsPhone(candidate, phone));
        return candidate;
    }
}
