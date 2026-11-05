package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReservationCursorTest {

    @Test
    void 만든_커서를_그대로_풀면_같은_위치() {
        ReservationCursor cursor = new ReservationCursor(LocalDate.of(2026, 10, 10), 1042);

        assertThat(cursor.encode()).isEqualTo("MjAyNi0xMC0xMHwxMDQy");     // 13-7 예시
        assertThat(ReservationCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-10", "2026-10-10|", "|1042", "2026-10-10|abc", "2026-10-10|0", "2026-10-10|-1",
            "2026-13-01|1", "2026-02-30|1", "0999-12-31|1", "2026-10-10|9223372036854775808", "2026-10-10|1|2",
            "2026-10-10|1\n"})
    void 서버가_만들지_않는_내용이면_400(String plain) {
        assertInvalid(Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64!", "MjAyNi0xMC0xMHwxMDQy=", "MjAyNi0xMC0xMHwxMDQyA", "한글"})
    void Base64가_아니거나_같은_값의_다른_표기면_400(String value) {
        assertInvalid(value);
    }

    private static void assertInvalid(String value) {
        assertThatThrownBy(() -> ReservationCursor.decode(value))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
