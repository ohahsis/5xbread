package com.fivexbread.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoginPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(strings = {"PT1S", "PT10M", "PT24H"})
    void 잠금_시간은_1초_이상_초_단위면_통과(String duration) {
        assertThat(validator.validate(new LoginProperties(5, Duration.parse(duration), Duration.parse(duration)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT0.5S", "PT1.5S"})
    void 잠금_시간이_1초_미만이거나_초_단위가_아니면_거절(String duration) {
        Duration valid = Duration.ofMinutes(10);
        assertThat(validator.validate(new LoginProperties(5, Duration.parse(duration), valid))).hasSize(1);
        assertThat(validator.validate(new LoginProperties(5, valid, Duration.parse(duration)))).hasSize(1);
    }
}
