package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.test.context.TestPropertySource;

/** 동시 1,000건 — redis(v2, 기본·운영). Redis 잔여 = DB 계산값 */
@TestPropertySource(properties = "fivexbread.reservation.mode=redis")
class ConcurrentReservationRedisModeTest extends ConcurrentReservationTestBase {

    @Override
    ReservationMode mode() {
        return ReservationMode.REDIS;
    }

    @Override
    void assertRedisStock(int reservable, int reserved) {
        assertThat(Integer.parseInt(redis.opsForValue().get(stockKey()))).isEqualTo(reservable - reserved);
    }
}
