package com.fivexbread.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fivexbread.saledate.StockKeys;
import com.fivexbread.saledate.StockRedisSync;
import com.fivexbread.support.Concurrently;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

/** 폴러 묶음 처리·멱등·정리 (decisions.md 6장 "아웃박스 흐름"). 행은 SQL 로 직접 넣는다. 빵 1 재고 키를 10으로 두고 늘리기 이벤트를 쓴다 */
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

    private void insert(String type, String payload, LocalDateTime createdAt, LocalDateTime publishedAt) {
        jdbcTemplate.update("INSERT INTO outbox_event (event_type, payload, created_at, published_at) VALUES (?, ?, ?, ?)",
                type, payload, createdAt, publishedAt);
    }

    private static String increase(int amount) {
        return "{\"saleDate\":\"" + SALE_DATE + "\",\"increases\":{\"1\":" + amount + "}}";
    }

    private int pending() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Integer.class);
    }
}
