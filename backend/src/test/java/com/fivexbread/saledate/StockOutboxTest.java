package com.fivexbread.saledate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.fivexbread.common.outbox.Outbox;
import com.fivexbread.common.outbox.OutboxRelay;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
 * A-02 늘리기의 Redis 반영을 아웃박스로 (decisions.md 6장 "A-02 Redis 반영 순서" ④, "아웃박스 흐름").
 * HTTP 형식은 StockAdminApiTest 가 보고 여기서는 StockFacade 를 직접 부른다. 시드 빵 1·2, 날짜는 25일 뒤.
 */
@Import(MutableClockConfig.class)
class StockOutboxTest extends IntegrationTestSupport {

    @Autowired
    StockFacade stockFacade;

    @Autowired
    OutboxRelay outboxRelay;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    @MockitoSpyBean
    StockRedisSync stockRedisSync;

    @MockitoSpyBean
    Outbox outbox;

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(25);
        data.saleDate(date);
        data.stock(date, 1, 100, 60, 30, 4);
        data.stock(date, 2, 80, 50, 10, null);
        redis.opsForValue().set(StockKeys.stock(date, 1), "30");
        redis.opsForValue().set(StockKeys.stock(date, 2), "40");
        now = data.today().atTime(12, 0);
        mutableClock.set(now);
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 늘린_빵만_STOCK_INCREASE_행으로_적고_커밋_후_바로_INCRBY_한다() {
        stockFacade.put(date, 0, List.of(row(1, 70), row(2, 45)));

        Map<String, Object> event = jdbcTemplate.queryForMap("SELECT event_type, payload, published_at FROM outbox_event");
        assertThat(event.get("event_type")).isEqualTo("STOCK_INCREASE");
        assertThat(event.get("payload").toString()).contains("\"increases\": {\"1\": 10}");
        assertThat(event.get("published_at")).isNotNull();
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 1))).isEqualTo("40");
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 2))).isEqualTo("35");    // 줄이기는 커밋 전 DECRBY
    }

    @Test
    void 줄이기만_하면_아웃박스_행이_없다() {
        stockFacade.put(date, 0, List.of(row(1, 55)));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 1))).isEqualTo("25");
    }

    @Test
    void 바로_처리가_실패해도_저장은_성공하고_폴러가_한_번만_INCRBY_한다() {
        doThrow(new RedisConnectionFailureException("Redis 일시 장애")).doCallRealMethod().when(stockRedisSync)
                .increaseOnce(anyLong(), any(), any(), any());

        stockFacade.put(date, 0, List.of(row(1, 70)));
        assertThat(reservable(1)).isEqualTo(70);
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 1))).isEqualTo("30");

        mutableClock.set(now.plusSeconds(10));
        assertThat(outboxRelay.publishPending()).isEqualTo(1);
        jdbcTemplate.update("UPDATE outbox_event SET published_at = NULL");           // 발행 표시 전에 죽었다고 치고 한 번 더
        assertThat(outboxRelay.publishPending()).isEqualTo(1);

        assertThat(redis.opsForValue().get(StockKeys.stock(date, 1))).isEqualTo("40");
    }

    @Test
    void 아웃박스_INSERT_뒤_저장_트랜잭션이_롤백되면_행도_없고_DB와_Redis는_그대로다() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("INSERT 뒤 실패");
        }).when(outbox).append(anyString(), any());

        assertThatThrownBy(() -> stockFacade.put(date, 0, List.of(row(1, 70), row(2, 45))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
        assertThat(reservable(1)).isEqualTo(60);
        assertThat(data.version(date)).isZero();
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 1))).isEqualTo("30");
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 2))).isEqualTo("40");    // 먼저 줄인 5 를 보상
    }

    private static StockRowRequest row(long breadId, int reservable) {
        return new StockRowRequest(breadId, 100, reservable, null);
    }

    private int reservable(long breadId) {
        return jdbcTemplate.queryForObject("SELECT reservable_qty FROM daily_stock WHERE sale_date = ? AND bread_id = ?",
                Integer.class, date, breadId);
    }
}
