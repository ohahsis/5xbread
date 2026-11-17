package com.fivexbread.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 미발행·dead 행 수 gauge (decisions.md 6장 "업무 지표"). Grafana 경고 2개가 이 값을 본다.
 * 스크랩 때 DB 를 읽지 않고, 앱마다 metrics-interval 마다 읽어 둔 값을 내보낸다. 스크랩 때 읽으면 DB 장애 중에는 커넥션을
 * 기다리느라(Hikari 30초) 스크랩이 막혀 다른 메트릭까지 비기 때문이다. 읽지 못하면 이전 값을 그대로 둔다.
 * 락 없이 앱 3개가 각자 읽는다(값은 같다. Grafana 에서는 max).
 */
@Slf4j
@Component
public class OutboxMetrics {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();

    public OutboxMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder("fivexbread.outbox.pending", pending, AtomicLong::get)
                .description("아직 발행하지 않은 아웃박스 행 수 (dead 제외)").register(meterRegistry);
        Gauge.builder("fivexbread.outbox.dead", dead, AtomicLong::get)
                .description("처리 실패 한도에 닿아 더 시도하지 않는 아웃박스 행 수").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${fivexbread.outbox.metrics-interval}",
            initialDelayString = "${fivexbread.outbox.metrics-interval}")
    public void refresh() {
        try {
            // ix_outbox_pending(published_at, dead_at, id) 의 published_at IS NULL 범위만 읽는다
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT COALESCE(SUM(dead_at IS NULL), 0) AS pending, COALESCE(SUM(dead_at IS NOT NULL), 0) AS dead
                    FROM outbox_event WHERE published_at IS NULL""");
            pending.set(((Number) row.get("pending")).longValue());
            dead.set(((Number) row.get("dead")).longValue());
        } catch (RuntimeException e) {
            log.warn("아웃박스 행 수를 읽지 못해 이전 값을 유지 pending={} dead={} cause={}", pending.get(), dead.get(), e.toString());
        }
    }
}
