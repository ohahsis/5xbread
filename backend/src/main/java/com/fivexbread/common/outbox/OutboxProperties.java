package com.fivexbread.common.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 트랜잭셔널 아웃박스 (decisions.md 6장 "아웃박스 흐름").
 *
 * @param relayDelay   폴러는 생성 후 이 시간이 지난 행만 집는다. 커밋 후 바로 처리하는 경로와 겹치지 않게 하려는 여유
 * @param batchSize    한 번에 읽는 행 수
 * @param maxBatches   폴러 1회 실행에서 읽는 최대 묶음 수 (batchSize × maxBatches 건까지)
 * @param retention    발행한 행을 지우기 전까지 보관하는 기간
 */
@Validated
@ConfigurationProperties("fivexbread.outbox")
public record OutboxProperties(@NotNull Duration relayDelay, @Min(1) int batchSize, @Min(1) int maxBatches,
                               @NotNull Duration retention) {
}
