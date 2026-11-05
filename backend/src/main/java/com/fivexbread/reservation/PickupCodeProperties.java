package com.fivexbread.reservation;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param secret 픽업 QR HMAC-SHA256 키 (C-07). PICKUP_SECRET 환경변수(로컬은 .env)로만 주입, UTF-8 32바이트 이상.
 *               비어 있거나 짧으면 앱이 뜨지 않는다 (JWT_SECRET 과 같은 방식). JWT 키와 따로 둬 한쪽이 새어도 다른 쪽은 안전하다
 */
@Validated
@ConfigurationProperties("fivexbread.pickup")
public record PickupCodeProperties(@NotBlank String secret) {
}
