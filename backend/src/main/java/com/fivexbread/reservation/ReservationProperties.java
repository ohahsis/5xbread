package com.fivexbread.reservation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예약 설정값 (부록 B: 설정 파일)
 *
 * @param dailyLimitPerMember 판매일별 1인 최대 예약 건수 (3건)
 * @param maxTotalQuantity    예약 1건 최대 총수량 (Q-07: 20개)
 * @param rateLimitPerSecond  회원당 초당 예약 요청 수 (N-23: 5회)
 * @param idempotencyTtl      Idempotency 결과 보관 (N-06: 24시간)
 */
@ConfigurationProperties("fivexbread.reservation")
public record ReservationProperties(int dailyLimitPerMember, int maxTotalQuantity, int rateLimitPerSecond,
                                    Duration idempotencyTtl) {
}
