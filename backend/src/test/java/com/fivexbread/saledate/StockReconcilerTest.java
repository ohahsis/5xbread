package com.fivexbread.saledate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.fivexbread.common.outbox.OutboxRelay;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 정합성 점검 (N-05, decisions.md 6장 "N-05 연속 2회 관측"). 스케줄 락 없이 StockReconciler 를 직접 부르고, 회차 사이는 시계를 5분 옮긴다.
 * 판매일은 20일 뒤(예약 중). DB 기준 잔여: 빵 1 = 50 − 10 = 40, 빵 2 = 30, 빵 3 = 20(Redis 키 없음).
 */
@Import(MutableClockConfig.class)
class StockReconcilerTest extends IntegrationTestSupport {

    @Autowired
    StockReconciler reconciler;

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
    StockRedisSync stockRedisSync;                              // 관측과 보정 사이에 예약이 끼어드는 상황

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        now = data.today().atTime(12, 0);
        date = data.today().plusDays(20);
        data.saleDate(date, now.minusHours(1), date.minusDays(1).atTime(20, 0), false);
        data.stock(date, 1, 100, 50, 10, null);
        data.stock(date, 2, 100, 30, 0, null);
        data.stock(date, 3, 100, 20, 0, null);
        set(date, 1, 40);
        set(date, 2, 30);
        mutableClock.set(now);
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 같은_차이를_5분_간격으로_2번_관측하면_차이만큼_보정하고_1번으로는_보정하지_않는다() {
        set(date, 1, 35);                                        // 보상 실패 등으로 Redis 가 5 적음

        assertThat(reconciler.runScheduled()).isZero();
        assertThat(stock(date, 1)).isEqualTo(35);
        assertThat(redis.hasKey(StockKeys.reconcileSeen(date, 1))).isTrue();

        after(5);
        assertThat(reconciler.runScheduled()).isEqualTo(1);
        assertThat(stock(date, 1)).isEqualTo(40);
        assertThat(stock(date, 2)).isEqualTo(30);
        assertThat(redis.hasKey(StockKeys.reconcileSeen(date, 1))).isFalse();
        assertThat(redis.opsForValue().get(StockKeys.loadedAt(date, 1)))           // 보정 시각을 적재 시각으로
                .isEqualTo(String.valueOf(StockKeys.epochMillis(now.plusMinutes(5))));

        after(10);
        assertThat(reconciler.runScheduled()).isZero();                            // 맞춘 뒤에는 그대로
        assertThat(stock(date, 1)).isEqualTo(40);
    }

    @Test
    void Redis가_DB보다_많아도_차이만큼_줄인다() {
        set(date, 2, 33);

        reconciler.runScheduled();
        after(5);
        reconciler.runScheduled();

        assertThat(stock(date, 2)).isEqualTo(30);
    }

    @Test
    void 진행_중인_예약으로_Redis만_줄어든_차이는_DB가_따라오면_보정하지_않는다() {
        set(date, 1, 37);                                        // Redis 차감 3, DB 커밋 전
        reconciler.runScheduled();

        jdbcTemplate.update("UPDATE daily_stock SET reserved_qty = 13 WHERE sale_date = ? AND bread_id = 1", date);
        after(5);
        reconciler.runScheduled();

        assertThat(stock(date, 1)).isEqualTo(37);
        assertThat(redis.hasKey(StockKeys.reconcileSeen(date, 1))).isFalse();
    }

    @Test
    void 차이가_바뀌면_처음부터_다시_센다() {
        set(date, 1, 35);
        reconciler.runScheduled();

        set(date, 1, 36);                                        // 차이 5 → 4
        after(5);
        reconciler.runScheduled();
        assertThat(stock(date, 1)).isEqualTo(36);

        after(10);
        reconciler.runScheduled();
        assertThat(stock(date, 1)).isEqualTo(40);
    }

    @Test
    void 최소_간격보다_가까운_재관측은_2번으로_세지_않고_첫_관측을_유지한다() {
        set(date, 1, 35);
        reconciler.runScheduled();

        after(1);                                                // 시계가 어긋난 앱의 중복 실행 등
        reconciler.runScheduled();
        assertThat(stock(date, 1)).isEqualTo(35);

        after(5);                                                // 첫 관측에서 5분
        reconciler.runScheduled();
        assertThat(stock(date, 1)).isEqualTo(40);
    }

    @Test
    void 보정은_SET이_아니라_INCRBY라_관측과_보정_사이의_차감이_남는다() {
        set(date, 1, 35);
        reconciler.runScheduled();
        doAnswer(invocation -> {
            redis.opsForValue().decrement(StockKeys.stock(date, 1));        // 그사이 예약 1개가 Redis 에서 빠짐
            return invocation.callRealMethod();
        }).when(stockRedisSync).adjust(any(), anyLong(), anyInt());

        after(5);
        reconciler.runScheduled();

        assertThat(stock(date, 1)).isEqualTo(39);                // 34 + 5. SET 이면 40 이 되어 그 예약의 차감이 사라진다
    }

    @Test
    void 재고_키가_없는_빵은_비교하지_않고_만들지도_않는다() {
        reconciler.runScheduled();
        after(5);
        reconciler.runScheduled();

        assertThat(redis.hasKey(StockKeys.stock(date, 3))).isFalse();
        assertThat(redis.hasKey(StockKeys.reconcileSeen(date, 3))).isFalse();
    }

    @Test
    void 휴무일과_마감이_지난_판매일은_점검하지_않는다() {
        LocalDate holiday = date.plusDays(1);
        data.saleDate(holiday, now.minusHours(1), holiday.minusDays(1).atTime(20, 0), true);
        data.stock(holiday, 1, 100, 50, 0, null);
        set(holiday, 1, 1);
        LocalDate closed = date.plusDays(2);
        data.saleDate(closed, now.minusDays(2), now.minusMinutes(1), false);
        data.stock(closed, 1, 100, 50, 0, null);
        set(closed, 1, 1);

        reconciler.runScheduled();
        after(5);
        reconciler.runScheduled();

        assertThat(stock(holiday, 1)).isEqualTo(1);
        assertThat(stock(closed, 1)).isEqualTo(1);
    }

    @Test
    void 미발행_아웃박스_이벤트가_있어도_보정하고_그_이벤트가_나중에_처리돼도_두_번_더하지_않는다() {
        // 취소가 커밋돼 DB 잔여는 43 인데 Redis 복구 이벤트가 처리되지 않은 채 남음(Redis 장애 등)
        jdbcTemplate.update("UPDATE daily_stock SET reserved_qty = 7 WHERE sale_date = ? AND bread_id = 1", date);
        jdbcTemplate.update("INSERT INTO outbox_event (event_type, payload, created_at) VALUES ('STOCK_RELEASE', ?, ?)",
                "{\"reservationId\":1,\"saleDate\":\"" + date + "\",\"memberId\":1,\"quantities\":{\"1\":3},"
                        + "\"generation\":null}", now.minusMinutes(1));

        reconciler.runScheduled();
        after(5);
        reconciler.runScheduled();
        assertThat(stock(date, 1)).isEqualTo(43);

        after(6);
        assertThat(outboxRelay.publishPending()).isEqualTo(1);              // 적재 시각(보정 시각)보다 먼저 생긴 이벤트
        assertThat(stock(date, 1)).isEqualTo(43);                          // 46 이 되지 않음

        after(10);
        reconciler.runScheduled();
        assertThat(stock(date, 1)).isEqualTo(43);
    }

    @Test
    void 같은_판매일의_재계산이_락을_잡고_있으면_이번_회차는_건너뛴다() {
        set(date, 1, 35);
        reconciler.runScheduled();
        SimpleLock lock = reconciler.tryLock(date).orElseThrow();
        try {
            after(5);
            assertThat(reconciler.runScheduled()).isZero();
            assertThat(stock(date, 1)).isEqualTo(35);
        } finally {
            lock.unlock();
        }
        assertThat(reconciler.tryLock(date)).isPresent().get().satisfies(SimpleLock::unlock);
    }

    private void after(int minutes) {
        mutableClock.set(now.plusMinutes(minutes));
    }

    private void set(LocalDate saleDate, long breadId, int remaining) {
        redis.opsForValue().set(StockKeys.stock(saleDate, breadId), String.valueOf(remaining));
    }

    private int stock(LocalDate saleDate, long breadId) {
        return Integer.parseInt(redis.opsForValue().get(StockKeys.stock(saleDate, breadId)));
    }
}
