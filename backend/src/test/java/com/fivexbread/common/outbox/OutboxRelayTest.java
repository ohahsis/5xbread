package com.fivexbread.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fivexbread.domain.saledate.StockKeys;
import com.fivexbread.domain.saledate.StockRedisSync;
import com.fivexbread.support.Concurrently;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 폴러 묶음 처리·멱등·정리·실패 한도 (decisions.md 6장 "아웃박스 흐름"·"아웃박스 실패 한도"). 행은 SQL 로 직접 넣는다.
 * 빵 1 재고 키를 10으로 두고 늘리기 이벤트를 쓴다. 실패 한도는 기본값 10
 */
@Import(MutableClockConfig.class)
class OutboxRelayTest extends IntegrationTestSupport {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);
    private static final LocalDate SALE_DATE = LocalDate.of(2026, 11, 20);

    @Autowired
    OutboxRelay outboxRelay;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MutableClock mutableClock;

    @Autowired
    OutboxMetrics outboxMetrics;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoSpyBean
    StockRedisSync stockRedisSync;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        mutableClock.set(NOW);
        redis.opsForValue().set(StockKeys.stock(SALE_DATE, 1), "10");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        mutableClock.reset();
    }

    @Test
    void 폴러_4개가_동시에_같은_행들을_처리해도_이벤트마다_한_번만_적용한다() throws Exception {
        for (int i = 0; i < 50; i++) {
            insert("STOCK_INCREASE", increase(1), NOW.minusMinutes(1), null);
        }
        List<Callable<Integer>> pollers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pollers.add(outboxRelay::publishPending);
        }

        Concurrently.run(pollers, 4);

        assertThat(redis.opsForValue().get(StockKeys.stock(SALE_DATE, 1))).isEqualTo("60");
        assertThat(pending()).isZero();
    }

    @Test
    void 생성_후_10초가_지나지_않은_행은_집지_않는다() {
        insert("STOCK_INCREASE", increase(1), NOW.minusSeconds(9), null);
        insert("STOCK_INCREASE", increase(2), NOW.minusSeconds(10), null);

        assertThat(outboxRelay.publishPending()).isEqualTo(1);

        assertThat(redis.opsForValue().get(StockKeys.stock(SALE_DATE, 1))).isEqualTo("12");
        assertThat(pending()).isEqualTo(1);
    }

    @Test
    void 처리할_수_없는_행은_남기고_뒤의_행은_처리한다() {
        insert("UNKNOWN", "{}", NOW.minusMinutes(1), null);
        insert("STOCK_INCREASE", "{\"saleDate\":\"내일\"}", NOW.minusMinutes(1), null);   // 읽을 수 없는 payload
        insert("STOCK_INCREASE", increase(5), NOW.minusMinutes(1), null);

        assertThat(outboxRelay.publishPending()).isEqualTo(1);

        assertThat(redis.opsForValue().get(StockKeys.stock(SALE_DATE, 1))).isEqualTo("15");
        assertThat(pending()).isEqualTo(2);
    }

    @Test
    void Redis_연결_실패면_남은_행을_시도하지_않고_멈춘다() {
        insert("STOCK_INCREASE", increase(1), NOW.minusMinutes(1), null);
        insert("STOCK_INCREASE", increase(2), NOW.minusMinutes(1), null);
        doThrow(new RedisConnectionFailureException("Redis 장애")).when(stockRedisSync)
                .increaseOnce(anyLong(), any(), any(), any());

        assertThat(outboxRelay.publishPending()).isZero();

        verify(stockRedisSync, times(1)).increaseOnce(anyLong(), any(), any(), any());
        assertThat(pending()).isEqualTo(2);
        assertThat(attemptsInIdOrder()).containsExactly(1, 0);      // 시도는 실패한 맨 앞 행에만 센다
    }

    @Test
    void 실패마다_attempts_를_올리고_10번째_실패에서_dead_로_표시해_더_집지_않는다() {
        long id = insert("UNKNOWN", "{}", NOW.minusMinutes(1), null);
        double deadBefore = deadEvents();

        for (int i = 0; i < 9; i++) {
            outboxRelay.publishPending();
        }
        assertThat(row(id)).containsEntry("attempts", 9).containsEntry("dead_at", null);
        assertThat((String) row(id).get("last_error")).startsWith("IllegalStateException: 처리할 수 없는 아웃박스 이벤트");

        mutableClock.set(NOW.plusMinutes(1));
        outboxRelay.publishPending();

        assertThat(row(id)).containsEntry("attempts", 10).containsEntry("dead_at", NOW.plusMinutes(1));
        assertThat(deadEvents()).isEqualTo(deadBefore + 1);

        outboxRelay.publishPending();
        outboxRelay.publish(id);
        assertThat(row(id)).containsEntry("attempts", 10);          // 폴러도 바로 처리도 집지 않는다
        assertThat(deadEvents()).isEqualTo(deadBefore + 1);
    }

    @Test
    void 바로_처리_실패도_1회로_센다() {
        long id = insert("STOCK_INCREASE", increase(1), NOW, null);
        doThrow(new RedisConnectionFailureException("Redis 장애")).when(stockRedisSync)
                .increaseOnce(anyLong(), any(), any(), any());

        outboxRelay.publish(id);

        assertThat(row(id)).containsEntry("attempts", 1).containsEntry("published_at", null);
        assertThat((String) row(id).get("last_error")).isEqualTo("RedisConnectionFailureException: Redis 장애");
    }

    @Test
    void last_error_는_500자에서_자른다() {
        long id = insert("STOCK_INCREASE", increase(1), NOW.minusMinutes(1), null);
        doThrow(new IllegalStateException("가".repeat(1_000))).when(stockRedisSync)
                .increaseOnce(anyLong(), any(), any(), any());

        outboxRelay.publishPending();

        assertThat((String) row(id).get("last_error")).hasSize(500).startsWith("IllegalStateException: 가가");
    }

    @Test
    void 실패한_뒤_성공하면_발행되고_attempts_는_남는다() {
        long id = insert("STOCK_INCREASE", increase(3), NOW.minusMinutes(1), null);
        doThrow(new RedisConnectionFailureException("Redis 장애")).doCallRealMethod().when(stockRedisSync)
                .increaseOnce(anyLong(), any(), any(), any());

        outboxRelay.publishPending();
        outboxRelay.publishPending();

        assertThat(row(id).get("published_at")).isNotNull();
        assertThat(row(id)).containsEntry("attempts", 1).containsEntry("dead_at", null);
        assertThat(redis.opsForValue().get(StockKeys.stock(SALE_DATE, 1))).isEqualTo("13");
    }

    @Test
    void 이미_dead_인_행은_바로_처리도_집지_않는다() {
        long id = insert("STOCK_INCREASE", increase(1), NOW, null);
        jdbcTemplate.update("UPDATE outbox_event SET attempts = 10, dead_at = ? WHERE id = ?", NOW, id);

        outboxRelay.publish(id);

        verify(stockRedisSync, never()).increaseOnce(anyLong(), any(), any(), any());
        assertThat(redis.opsForValue().get(StockKeys.stock(SALE_DATE, 1))).isEqualTo("10");
    }

    @Test
    void 미발행_gauge_는_dead_를_빼고_dead_gauge_는_dead_만_센다() {
        insert("STOCK_INCREASE", increase(1), NOW, null);
        insert("STOCK_INCREASE", increase(1), NOW, null);
        long dead = insert("STOCK_INCREASE", increase(1), NOW, null);
        insert("STOCK_INCREASE", increase(1), NOW, NOW);              // 발행함
        jdbcTemplate.update("UPDATE outbox_event SET attempts = 10, dead_at = ? WHERE id = ?", NOW, dead);

        outboxMetrics.refresh();

        assertThat(meterRegistry.get("fivexbread.outbox.pending").gauge().value()).isEqualTo(2);
        assertThat(meterRegistry.get("fivexbread.outbox.dead").gauge().value()).isEqualTo(1);
    }

    @Test
    void 보관_기간_7일이_지난_발행_행만_지우고_미발행_행은_오래돼도_남긴다() {
        insert("STOCK_INCREASE", increase(1), NOW.minusDays(9), NOW.minusDays(7).minusSeconds(1));
        insert("STOCK_INCREASE", increase(1), NOW.minusDays(9), NOW.minusDays(6));
        insert("STOCK_INCREASE", increase(1), NOW.minusDays(30), null);

        assertThat(outboxRelay.deletePublished()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(2);
        assertThat(pending()).isEqualTo(1);
    }

    private long insert(String type, String payload, LocalDateTime createdAt, LocalDateTime publishedAt) {
        jdbcTemplate.update("INSERT INTO outbox_event (event_type, payload, created_at, published_at) VALUES (?, ?, ?, ?)",
                type, payload, createdAt, publishedAt);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM outbox_event", Long.class);
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap(
                "SELECT attempts, last_error, dead_at, published_at FROM outbox_event WHERE id = ?", id);
    }

    private List<Integer> attemptsInIdOrder() {
        return jdbcTemplate.queryForList("SELECT attempts FROM outbox_event ORDER BY id", Integer.class);
    }

    private double deadEvents() {
        return meterRegistry.get("fivexbread.outbox.dead.events").counter().count();
    }

    private static String increase(int amount) {
        return "{\"saleDate\":\"" + SALE_DATE + "\",\"increases\":{\"1\":" + amount + "}}";
    }

    private int pending() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Integer.class);
    }
}
