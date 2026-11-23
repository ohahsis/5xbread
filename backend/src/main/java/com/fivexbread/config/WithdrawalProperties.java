package com.fivexbread.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 탈퇴 (AU-07, decisions.md 6장 "AU-07 탈퇴").
 *
 * @param phoneHashSecret 탈퇴한 번호의 HMAC-SHA256 키. WITHDRAWN_PHONE_SECRET 환경변수(로컬은 .env)로만 주입, UTF-8 32바이트 이상.
 *                        비어 있거나 짧으면 앱이 뜨지 않는다. 키 없는 해시는 010 번호(약 1억 개)를 몇 초 만에 되돌릴 수 있어 키를 둔다
 * @param rejoinAfter     같은 번호로 다시 가입할 수 있을 때까지 (부록 B: 30일, 설정 파일). 이 기간이 지난 해시는 정리 작업이 지운다
 */
@Validated
@ConfigurationProperties("fivexbread.withdrawal")
public record WithdrawalProperties(@NotBlank String phoneHashSecret, @NotNull Duration rejoinAfter) {
}
