package com.fivexbread.saledate;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 정합성 점검·수량 재계산 (N-05, A-12, decisions.md 6장 "N-05 연속 2회 관측"·"A-12 진행 중 예약과의 경합").
 *
 * @param minGap             두 관측 사이 최소 간격. 이보다 짧게 다시 관측하면(시계가 어긋난 앱의 중복 실행 등) 2회로 세지 않는다
 * @param observationTtl     첫 관측의 보관 시간. 다음 회차(5분 뒤)까지는 남고, 한 회차를 건너뛰면 사라져 처음부터 다시 센다
 * @param manualConfirmDelay A-12 가 두 번 관측하는 간격. 진행 중인 예약(Redis 차감 → DB 커밋, 보통 수십 ms)이 끝나기를 기다린다
 */
@Validated
@ConfigurationProperties("fivexbread.reconcile")
public record ReconcileProperties(@NotNull Duration minGap, @NotNull Duration observationTtl,
                                  @NotNull Duration manualConfirmDelay) {
}
