package com.fivexbread.reservation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * fivexbread_reservation_limit_db_reject_total: 예약 v2 에서 Redis Lua 는 통과했지만 DB 재검사(하루 건수·1인 한도)에서
 * 막힌 요청 수 (decisions.md 6장 "예약 v2 한도 DB 재검사"). 0 이 아니면 Redis user_qty 가 DB 보다 느슨하게 어긋난 것이다.
 * 태그 reason = daily_count | per_user_limit
 */
@Component
class ReservationLimitMetrics {

    static final String DAILY_COUNT = "daily_count";
    static final String PER_USER_LIMIT = "per_user_limit";

    private final MeterRegistry meterRegistry;

    ReservationLimitMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        counter(DAILY_COUNT);                   // 막힌 적이 없어도 0 으로 보이게 미리 만든다
        counter(PER_USER_LIMIT);
    }

    void dbReject(String reason) {
        counter(reason).increment();
    }

    private Counter counter(String reason) {
        return Counter.builder("fivexbread.reservation.limit.db.reject")
                .description("Redis 는 통과했지만 DB 재검사에서 막힌 예약 수").tag("reason", reason).register(meterRegistry);
    }
}
