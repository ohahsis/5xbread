package com.fivexbread.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fivexbread.member.SignupProperties;
import com.fivexbread.reservation.ReservationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 설정 값이 잘못되면 앱이 뜨지 않는다 (@Validated). 0·빈 값이 조용히 들어가 한도가 사라지는 것을 막는다 */
class PropertiesValidationTest {

    private static final String[] VALID_RESERVATION = {
            "fivexbread.reservation.daily-limit-per-member=3",
            "fivexbread.reservation.max-total-quantity=20",
            "fivexbread.reservation.rate-limit-per-second=5",
            "fivexbread.reservation.idempotency-ttl=24h"};

    private static final String[] VALID_SIGNUP = {
            "fivexbread.signup.daily-limit-per-ip=10",
            "fivexbread.signup.terms-version=v1",
            "fivexbread.signup.privacy-version=v1"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties({ReservationProperties.class, SignupProperties.class})
    static class Config {
    }

    @Test
    void 올바른_값이면_뜨고_기본값이_들어간다() {
        runner.withPropertyValues(VALID_RESERVATION).withPropertyValues(VALID_SIGNUP).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ReservationProperties.class).idempotencyProcessingTtl())
                    .isEqualTo(Duration.ofMinutes(2));
        });
    }

    @Test
    void 예약_한도가_0이거나_TTL이_없으면_뜨지_않는다() {
        runner.withPropertyValues(VALID_RESERVATION).withPropertyValues(VALID_SIGNUP)
                .withPropertyValues("fivexbread.reservation.daily-limit-per-member=0")
                .run(context -> assertThat(context).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
        runner.withPropertyValues(VALID_SIGNUP)
                .withPropertyValues("fivexbread.reservation.daily-limit-per-member=3",
                        "fivexbread.reservation.max-total-quantity=20",
                        "fivexbread.reservation.rate-limit-per-second=5")
                .run(context -> assertThat(context).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void 가입_한도가_0이거나_약관_버전이_비면_뜨지_않는다() {
        runner.withPropertyValues(VALID_RESERVATION).withPropertyValues(VALID_SIGNUP)
                .withPropertyValues("fivexbread.signup.daily-limit-per-ip=0")
                .run(context -> assertThat(context).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
        runner.withPropertyValues(VALID_RESERVATION).withPropertyValues(VALID_SIGNUP)
                .withPropertyValues("fivexbread.signup.terms-version=")
                .run(context -> assertThat(context).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }
}
