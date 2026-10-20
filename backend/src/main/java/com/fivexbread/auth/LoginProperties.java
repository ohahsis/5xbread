package com.fivexbread.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxFailures      연속으로 이 횟수만큼 틀리면 잠근다 (AU-05, 부록 B: 5)
 * @param failureRetention 실패 카운터 정리용 TTL. 실패할 때마다 갱신 (decisions.md 6장: 24시간)
 * @param lockDuration     maxFailures 번째 실패부터의 잠금 시간 (10분)
 */
@Validated
@ConfigurationProperties("fivexbread.login")
public record LoginProperties(@Positive int maxFailures, @NotNull Duration failureRetention, @NotNull Duration lockDuration) {

    /**
     * Redis EXPIRE 는 초 단위라 1초 미만은 0으로 잘리고, EXPIRE 0 은 키를 바로 지워 잠금이 동작하지 않는다.
     * 초 단위로 떨어지지 않는 값(1.5s 등)도 조용히 잘리지 않도록 막는다.
     */
    @AssertTrue(message = "failure-retention, lock-duration 은 1초 이상, 초 단위여야 합니다")
    public boolean isDurationsWholeSeconds() {
        return isWholeSeconds(failureRetention) && isWholeSeconds(lockDuration);
    }

    private static boolean isWholeSeconds(Duration duration) {
        return duration == null || (duration.compareTo(Duration.ofSeconds(1)) >= 0 && duration.toMillisPart() == 0);
    }
}
