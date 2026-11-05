package com.fivexbread.common;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * 응답·로그에 내보내는 손님 이름 (AU-10, 13-9 "김*건"). 마스킹한 값만 가지므로 원문이 새어 나갈 수 없다.
 * 첫 글자와 마지막 글자만 남긴다: 김민건 → 김*건, 남궁민수 → 남**수. 두 글자는 첫 글자만(이건 → 이*), 한 글자는 *.
 * 글자 수는 코드 포인트로 센다 (이모지 등이 반으로 잘리지 않게).
 */
public final class MaskedName {

    private final String value;

    private MaskedName(String value) {
        this.value = value;
    }

    public static MaskedName from(String name) {
        if (name == null || name.isBlank()) {
            return new MaskedName(null);
        }
        int[] chars = name.strip().codePoints().toArray();
        if (chars.length == 1) {
            return new MaskedName("*");
        }
        StringBuilder masked = new StringBuilder().appendCodePoint(chars[0]);
        if (chars.length == 2) {
            return new MaskedName(masked.append('*').toString());
        }
        masked.append("*".repeat(chars.length - 2)).appendCodePoint(chars[chars.length - 1]);
        return new MaskedName(masked.toString());
    }

    @JsonValue
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MaskedName other && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
