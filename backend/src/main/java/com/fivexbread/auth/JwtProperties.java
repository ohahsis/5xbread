package com.fivexbread.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param secret         HS256 서명 키. JWT_SECRET 환경변수(로컬은 .env)로만 주입. UTF-8 32바이트 이상
 * @param accessTokenTtl Access Token 수명 (정의서 5장: 30분)
 */
@Validated
@ConfigurationProperties("fivexbread.jwt")
public record JwtProperties(@NotBlank String secret, @NotNull Duration accessTokenTtl) {
}
