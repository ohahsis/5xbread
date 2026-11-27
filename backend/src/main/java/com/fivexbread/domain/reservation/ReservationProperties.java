package com.fivexbread.domain.reservation;

import com.fivexbread.domain.reservation.model.enums.ReservationMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 예약 설정값 (부록 B: 설정 파일)
 *
 * @param dailyLimitPerMember 판매일별 1인 최대 예약 건수 (3건)
 * @param maxTotalQuantity    예약 1건 최대 총수량 (Q-07: 20개)
 * @param rateLimitPerSecond  회원당 초당 예약 요청 수 (N-23: 5회)
 * @param idempotencyTtl      Idempotency 결과 보관 (N-06: 24시간)
 * @param idempotencyProcessingTtl 처리 중 표시(PROCESSING) 보관. 요청 하나를 처리하는 상한보다 길게 (decisions.md 6장 "Idempotency 처리 중 TTL")
 * @param mode                재고·한도 검사 방식. 기본 redis(v2). db-only(v1)는 비교 측정용 (ReservationMode)
 */
@Validated
@ConfigurationProperties("fivexbread.reservation")
public record ReservationProperties(@Positive int dailyLimitPerMember, @Positive int maxTotalQuantity,
                                    @Positive int rateLimitPerSecond, @NotNull Duration idempotencyTtl,
                                    @NotNull @DefaultValue("2m") Duration idempotencyProcessingTtl,
                                    @NotNull @DefaultValue("redis") ReservationMode mode) {
}
