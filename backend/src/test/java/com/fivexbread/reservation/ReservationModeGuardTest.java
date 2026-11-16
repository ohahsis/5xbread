package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** db-only(v1)는 docker·test 프로필에서만 켤 수 있다 (decisions.md 6장 "예약 측정 모드") */
class ReservationModeGuardTest {

    @Test
    void redis_모드는_어느_프로필에서나_뜬다() {
        assertThatCode(() -> ReservationModeGuard.check(ReservationMode.REDIS, env("local"))).doesNotThrowAnyException();
        assertThatCode(() -> ReservationModeGuard.check(ReservationMode.REDIS, env())).doesNotThrowAnyException();
    }

    @Test
    void db_only는_docker나_test_프로필이면_뜬다() {
        assertThatCode(() -> ReservationModeGuard.check(ReservationMode.DB_ONLY, env("docker"))).doesNotThrowAnyException();
        assertThatCode(() -> ReservationModeGuard.check(ReservationMode.DB_ONLY, env("test"))).doesNotThrowAnyException();
    }

    @Test
    void db_only를_local이나_프로필_없이_켜면_뜨지_않는다() {
        assertThatThrownBy(() -> ReservationModeGuard.check(ReservationMode.DB_ONLY, env("local")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("db-only");
        assertThatThrownBy(() -> ReservationModeGuard.check(ReservationMode.DB_ONLY, env()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }
}
