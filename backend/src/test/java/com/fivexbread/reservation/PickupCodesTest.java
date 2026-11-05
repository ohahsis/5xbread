package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** PICKUP_SECRET 이 비었거나 짧으면 앱이 뜨지 않는다 (JWT_SECRET 과 같은 방식), 다른 키로 서명한 토큰은 통과하지 않는다 */
class PickupCodesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties(PickupCodeProperties.class)
    @Import(PickupCodes.class)
    static class Config {
    }

    @Test
    void 키가_비어_있으면_뜨지_않는다() {
        runner.withPropertyValues("fivexbread.pickup.secret=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @Test
    void 키가_32바이트보다_짧으면_뜨지_않는다() {
        runner.withPropertyValues("fivexbread.pickup.secret=" + "k".repeat(31)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("32바이트");
        });
        runner.withPropertyValues("fivexbread.pickup.secret=" + "k".repeat(32)).run(context ->
                assertThat(context).hasNotFailed());
    }

    @Test
    void 다른_키로_서명한_토큰은_통과하지_않는다() {
        PickupCodes mine = new PickupCodes(new PickupCodeProperties("a".repeat(32)));
        PickupCodes other = new PickupCodes(new PickupCodeProperties("b".repeat(32)));
        LocalDate saleDate = LocalDate.of(2026, 10, 10);
        String code = other.issue(saleDate, "1010-0427").code();

        assertThat(mine.verify(code, saleDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant())).isEmpty();
        assertThat(other.verify(code, saleDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()))
                .hasValueSatisfying(v -> assertThat(v.saleDate()).isEqualTo(saleDate));
    }

    @Test
    void 키가_틀리면_예외_메시지에_키를_넣지_않는다() {
        assertThatThrownBy(() -> new PickupCodes(new PickupCodeProperties("short-secret-value")))
                .hasMessageNotContaining("short-secret-value");
    }
}
