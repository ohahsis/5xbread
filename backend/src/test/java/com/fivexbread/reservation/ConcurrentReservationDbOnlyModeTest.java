package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.test.context.TestPropertySource;

/** 동시 1,000건 — db-only(v1, 재고·한도 검사를 DB로). 초과 예약 0은 DB 조건부 UPDATE 만으로 지켜지고, Redis 재고 키는 건드리지 않는다 */
@TestPropertySource(properties = "fivexbread.reservation.mode=db-only")
class ConcurrentReservationDbOnlyModeTest extends ConcurrentReservationTestBase {

    @Override
    ReservationMode mode() {
        return ReservationMode.DB_ONLY;
    }

    @Override
    void assertRedisStock(int reservable, int reserved) {
        assertThat(redis.hasKey(stockKey())).isFalse();
    }
}
