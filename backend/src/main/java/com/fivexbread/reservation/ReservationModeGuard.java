package com.fivexbread.reservation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * db-only(v1)는 Redis 재고·실시간 잔여를 바꾸지 않아 목록 잔여가 틀어진다. 비교 측정(docker 프로필, load/compare.sh)과
 * 테스트(test 프로필)에서만 켤 수 있게, 그 밖의 프로필에서 켜면 앱이 뜨지 않는다 (decisions.md 6장 "예약 측정 모드").
 */
@Slf4j
@Component
class ReservationModeGuard {

    static final Profiles ALLOWED = Profiles.of("docker | test");

    ReservationModeGuard(ReservationProperties properties, Environment environment) {
        check(properties.mode(), environment);
    }

    static void check(ReservationMode mode, Environment environment) {
        if (mode != ReservationMode.DB_ONLY) {
            return;
        }
        if (!environment.acceptsProfiles(ALLOWED)) {
            throw new IllegalStateException(
                    "fivexbread.reservation.mode=db-only 는 비교 측정용이라 docker·test 프로필에서만 켤 수 있습니다");
        }
        log.warn("예약 모드 db-only(v1): 재고·한도 검사를 DB 로 한다. Redis 재고·실시간 잔여는 바뀌지 않는다(측정 전용)");
    }
}
