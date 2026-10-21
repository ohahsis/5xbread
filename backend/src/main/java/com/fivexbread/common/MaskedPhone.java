package com.fivexbread.common;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 응답·로그에 내보내는 휴대폰 번호 (AU-10). 마스킹한 값만 가지므로 원문이 새어 나갈 수 없다.
 * 01012345678 → 010-****-5678
 */
public final class MaskedPhone {

    private final String value;

    private MaskedPhone(String value) {
        this.value = value;
    }

    public static MaskedPhone from(String phone) {
        if (phone == null || phone.length() < 4) {
            return new MaskedPhone(null);
        }
        return new MaskedPhone(phone.substring(0, 3) + "-****-" + phone.substring(phone.length() - 4));
    }

    @JsonValue
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MaskedPhone other && java.util.Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
