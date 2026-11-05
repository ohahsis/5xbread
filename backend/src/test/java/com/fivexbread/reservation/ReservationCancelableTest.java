package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fivexbread.common.ClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** cancelable 경계 (R-13). 픽업 11:00 → 10:00:00 까지 가능, 10:00:01 부터 불가 */
class ReservationCancelableTest {

    private static final LocalDateTime PICKUP_AT = LocalDateTime.of(2026, 10, 10, 11, 0);
    private static final LocalDateTime DEADLINE = PICKUP_AT.minusMinutes(60);

    private final Reservation reservation = Reservation.create("1010-0001", 1, "0000", LocalDate.of(2026, 10, 10), 1,
            PICKUP_AT, 3500, "key", DEADLINE.minusDays(1));

    @Test
    void 마감_정각에는_취소할_수_있다() {
        assertThat(reservation.cancelable(now(DEADLINE))).isTrue();
    }

    @Test
    void 마감_1초_뒤에는_취소할_수_없다() {
        assertThat(reservation.cancelable(now(DEADLINE.plusSeconds(1)))).isFalse();
    }

    private static LocalDateTime now(LocalDateTime at) {
        return LocalDateTime.now(Clock.fixed(at.atZone(ClockConfig.KST).toInstant(), ClockConfig.KST));
    }
}
