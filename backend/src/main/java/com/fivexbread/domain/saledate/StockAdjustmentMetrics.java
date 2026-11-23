package com.fivexbread.domain.saledate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Redis 잔여 보정 지표 (N-05 정기 점검, A-12 재계산. decisions.md 6장 "업무 지표").
 * <ul>
 *   <li>fivexbread_reconcile_adjustments_total: 보정한 (판매일, 빵) 수</li>
 *   <li>fivexbread_reconcile_adjusted_quantity_total: 보정량의 절댓값 합</li>
 * </ul>
 * 태그 source = scheduled(N-05) | manual(A-12), direction = redis_low(Redis 가 적어 늘림) | redis_high(많아 줄임)
 */
@Component
public class StockAdjustmentMetrics {

    public static final String SCHEDULED = "scheduled";
    public static final String MANUAL = "manual";

    private final MeterRegistry meterRegistry;

    public StockAdjustmentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (String source : new String[] {SCHEDULED, MANUAL}) {       // 보정이 없어도 0 으로 보이게 미리 만든다
            for (String direction : new String[] {"redis_low", "redis_high"}) {
                adjustments(source, direction);
                quantity(source, direction);
            }
        }
    }

    /** @param delta DB − Redis 로 더한 값 (0 이 아님) */
    public void record(String source, int delta) {
        String direction = delta > 0 ? "redis_low" : "redis_high";
        adjustments(source, direction).increment();
        quantity(source, direction).increment(Math.abs(delta));
    }

    private Counter adjustments(String source, String direction) {
        return Counter.builder("fivexbread.reconcile.adjustments").description("Redis 잔여를 보정한 (판매일, 빵) 수")
                .tags("source", source, "direction", direction).register(meterRegistry);
    }

    private Counter quantity(String source, String direction) {
        return Counter.builder("fivexbread.reconcile.adjusted.quantity").description("Redis 잔여 보정량(절댓값) 합")
                .tags("source", source, "direction", direction).register(meterRegistry);
    }
}
