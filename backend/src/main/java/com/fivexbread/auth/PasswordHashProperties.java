package com.fivexbread.auth;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxConcurrency 앱 1개에서 동시에 도는 BCrypt 계산 상한. 0이면 JVM 이 보는 CPU 수
 */
@Validated
@ConfigurationProperties("fivexbread.password-hash")
public record PasswordHashProperties(@PositiveOrZero int maxConcurrency) {

    public int permits() {
        return maxConcurrency > 0 ? maxConcurrency : Runtime.getRuntime().availableProcessors();
    }
}
