package com.fivexbread.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fivexbread.common.BusinessException;
import com.fivexbread.reservation.NoShowService;
import com.fivexbread.reservation.ReservationPickupService;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.saledate.StockKeys;
import com.fivexbread.support.Concurrently;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 노쇼 배치와 R-17 (A-07, 13-10). 스케줄을 기다리지 않고 시계를 21:00 으로 고정해 NoShowBatch 를 직접 부른다.
 * today = 50일 뒤라 31일 전 판매일도 테스트 데이터 범위(10일 뒤부터) 안에 있다.
 */
@Import(MutableClockConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class NoShowBatchTest extends IntegrationTestSupport {

    private static final long ADMIN_ID = 9_000_001L;
    private static final long BREAD_ID = 3L;

    @Autowired
    NoShowBatch noShowBatch;

    @Autowired
    NoShowService noShowService;

    @Autowired
    ReservationPickupService pickupService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    private final Map<LocalDate, Long> slots = new HashMap<>();
    private SaleDateTestData data;
    private LocalDate today;
    private long member;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        slots.clear();
        today = data.today().plusDays(50);
        member = data.memberId();
        at(today);
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 오늘_RESERVED_만_NO_SHOW_가_되고_시스템_이력이_남는다() {
        long reserved = reservation(member, today, "RESERVED");
        long pickedUp = reservation(member, today, "PICKED_UP");
        long canceled = reservation(member, today, "CANCELED");
        long tomorrow = reservation(member, today.plusDays(1), "RESERVED");
        long yesterday = reservation(member, today.minusDays(1), "RESERVED");

        noShowBatch.run();

        assertThat(status(reserved)).isEqualTo("NO_SHOW");
        assertThat(status(pickedUp)).isEqualTo("PICKED_UP");
        assertThat(status(canceled)).isEqualTo("CANCELED");
        assertThat(status(tomorrow)).isEqualTo("RESERVED");
        assertThat(status(yesterday)).isEqualTo("RESERVED");

        Map<String, Object> history = jdbcTemplate.queryForMap(
                "SELECT from_status, to_status, actor_id, changed_at FROM reservation_history WHERE reservation_id = ?",
                reserved);
        assertThat(history.get("from_status")).isEqualTo("RESERVED");
        assertThat(history.get("to_status")).isEqualTo("NO_SHOW");
        assertThat(history.get("actor_id")).isNull();
        assertThat(((LocalDateTime) history.get("changed_at"))).isEqualTo(today.atTime(21, 0));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation_history WHERE reservation_id IN (?, ?, ?, ?)",
                Integer.class, pickedUp, canceled, tomorrow, yesterday)).isZero();
    }

    @Test
    void 재고는_DB도_Redis도_되돌리지_않는다() {
        long id = reservation(member, today, "RESERVED");
        data.stock(today, BREAD_ID, 100, 50, 2, null);
        data.item(id, BREAD_ID, 2);
        String stockKey = StockKeys.stock(today, BREAD_ID);
        String userQtyKey = StockKeys.userQty(today, member);
        redis.opsForValue().set(stockKey, "48");
        redis.opsForHash().put(userQtyKey, "count", "1");

        noShowBatch.run();

        assertThat(status(id)).isEqualTo("NO_SHOW");
        assertThat(jdbcTemplate.queryForObject("SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = ?",
                Integer.class, today, BREAD_ID)).isEqualTo(2);
        assertThat(redis.opsForValue().get(stockKey)).isEqualTo("48");
        assertThat(redis.opsForHash().get(userQtyKey, "count")).isEqualTo("1");
    }

    @Test
    void 묶음_크기를_넘는_2500건과_회원_1250명을_전부_처리한다() {
        List<Long> members = data.members(1_250);
        bulkReservations(members, today.minusDays(7), "NO_SHOW", 1);       // 2회째가 되도록 이전 노쇼 1일
        bulkReservations(members, today, "RESERVED", 2);

        noShowBatch.run();

        assertThat(count("SELECT COUNT(*) FROM reservation WHERE sale_date = ? AND status = 'NO_SHOW'")).isEqualTo(2_500);
        assertThat(count("SELECT COUNT(*) FROM reservation WHERE sale_date = ? AND status = 'RESERVED'")).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM reservation_history h JOIN reservation r ON r.id = h.reservation_id
                WHERE r.sale_date = ?""")).isEqualTo(2_500);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM member WHERE reservation_blocked_until = ? AND phone LIKE ?""",
                Integer.class, today.plusDays(14), SaleDateTestData.BULK_PHONE_PREFIX + "%")).isEqualTo(1_250);
    }

    @Test
    void 처리_건수를_로그로_남기고_개인정보는_남기지_않는다(CapturedOutput output) {
        reservation(member, today, "RESERVED");

        noShowBatch.run();

        assertThat(output).contains("노쇼 처리 완료 saleDate=" + today + " noShow=1 noShowMembers=1 blocked=0");
        assertThat(output).doesNotContain(SaleDateTestData.MEMBER_PHONE);
    }

    @Test
    void 대상이_없어도_0건으로_끝난다(CapturedOutput output) {
        noShowBatch.run();

        assertThat(output).contains("noShow=0 noShowMembers=0 blocked=0");
    }

    @Test
    void 두_번_실행해도_결과가_같다() {
        reservation(member, today.minusDays(7), "NO_SHOW");
        long id = reservation(member, today, "RESERVED");

        noShowBatch.run();
        LocalDate until = blockedUntil(member);
        at(today, 21, 5);
        noShowBatch.run();

        assertThat(status(id)).isEqualTo("NO_SHOW");
        assertThat(blockedUntil(member)).isEqualTo(until).isEqualTo(today.plusDays(14));
        assertThat(historyCount(id)).isEqualTo(1);
    }

    @Test
    void 묶음_처리_후_죽어도_다시_돌리면_남은_예약과_제한까지_처리한다() {
        reservation(member, today.minusDays(7), "NO_SHOW");
        long first = reservation(member, today, "RESERVED");
        long other = data.members(1).get(0);
        long second = reservation(other, today, "RESERVED");

        // 첫 묶음만 커밋하고 죽은 상황. member 의 노쇼는 이미 확정됐지만 R-17 은 아직이다
        assertThat(noShowService.markChunk(today, 1).marked()).isEqualTo(1);
        assertThat(status(first)).isEqualTo("NO_SHOW");
        assertThat(status(second)).isEqualTo("RESERVED");
        assertThat(blockedUntil(member)).isNull();

        noShowBatch.run();

        assertThat(status(second)).isEqualTo("NO_SHOW");
        assertThat(historyCount(first)).isEqualTo(1);
        assertThat(historyCount(second)).isEqualTo(1);
        assertThat(blockedUntil(member)).isEqualTo(today.plusDays(14));   // 재실행이 바꾼 행이 아니어도 대상에 든다
        assertThat(blockedUntil(other)).isNull();
    }

    @Test
    void 수령과_동시에_돌아도_예약마다_한쪽만_성공하고_이력은_1행이다() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (long m : data.members(120)) {
            ids.add(reservation(m, today, "RESERVED"));
        }
        List<Callable<Boolean>> tasks = new ArrayList<>();
        tasks.add(() -> {
            noShowBatch.run();
            return false;
        });
        for (long id : ids) {
            tasks.add(() -> {
                try {
                    pickupService.pickUp(id, ADMIN_ID);
                    return true;
                } catch (BusinessException alreadyNoShow) {
                    return false;
                }
            });
        }

        List<Boolean> results = Concurrently.run(tasks, 16);

        long pickedUp = results.stream().filter(success -> success).count();
        assertThat(count("SELECT COUNT(*) FROM reservation WHERE sale_date = ? AND status = 'PICKED_UP'")).isEqualTo(pickedUp);
        assertThat(count("SELECT COUNT(*) FROM reservation WHERE sale_date = ? AND status = 'NO_SHOW'"))
                .isEqualTo(ids.size() - pickedUp);
        for (long id : ids) {
            assertThat(jdbcTemplate.queryForObject("SELECT to_status FROM reservation_history WHERE reservation_id = ?",
                    String.class, id)).isEqualTo(status(id));
        }
    }

    @Test
    void R17_30일_안_1회면_제한하지_않는다() {
        reservation(member, today, "RESERVED");

        noShowBatch.run();

        assertThat(blockedUntil(member)).isNull();
    }

    @Test
    void R17_30일_안_2회면_오늘부터_14일_제한() {
        reservation(member, today.minusDays(29), "NO_SHOW");
        reservation(member, today, "RESERVED");

        noShowBatch.run();

        assertThat(blockedUntil(member)).isEqualTo(today.plusDays(14));
    }

    @Test
    void R17_30일_전_노쇼는_세고_31일_전_노쇼는_세지_않는다() {
        long within = data.members(1).get(0);
        reservation(within, today.minusDays(30), "NO_SHOW");
        reservation(within, today, "RESERVED");
        reservation(member, today.minusDays(31), "NO_SHOW");
        reservation(member, today, "RESERVED");

        noShowBatch.run();

        assertThat(blockedUntil(within)).isEqualTo(today.plusDays(14));
        assertThat(blockedUntil(member)).isNull();
    }

    @Test
    void R17_정의서_예시_10월_1일과_8일_노쇼면_10월_22일까지() {
        int year = data.today().getYear() + 1;
        reservation(member, LocalDate.of(year, 10, 1), "RESERVED");
        at(LocalDate.of(year, 10, 1));
        noShowBatch.run();
        assertThat(blockedUntil(member)).isNull();

        reservation(member, LocalDate.of(year, 10, 8), "RESERVED");
        at(LocalDate.of(year, 10, 8));
        noShowBatch.run();

        assertThat(blockedUntil(member)).isEqualTo(LocalDate.of(year, 10, 22));
    }

    @Test
    void R17_같은_날_예약_여러_건이_모두_노쇼여도_1회로_센다() {
        reservation(member, today, "RESERVED");
        reservation(member, today, "RESERVED");
        reservation(member, today, "RESERVED");

        noShowBatch.run();

        assertThat(blockedUntil(member)).isNull();
    }

    @Test
    void R17_다른_날_노쇼가_있으면_같은_날_여러_건은_합쳐서_2회다() {
        reservation(member, today.minusDays(7), "NO_SHOW");
        reservation(member, today.minusDays(7), "NO_SHOW");
        reservation(member, today, "RESERVED");

        noShowBatch.run();

        assertThat(blockedUntil(member)).isEqualTo(today.plusDays(14));
    }

    @Test
    void R17_취소나_수령은_노쇼로_세지_않는다() {
        reservation(member, today.minusDays(3), "CANCELED");
        reservation(member, today.minusDays(2), "PICKED_UP");
        reservation(member, today, "RESERVED");

        noShowBatch.run();

        assertThat(blockedUntil(member)).isNull();
    }

    @Test
    void R17_이미_더_늦은_제한은_줄이지_않고_더_이른_제한은_늘린다() {
        long earlier = data.members(1).get(0);
        for (long m : List.of(member, earlier)) {
            reservation(m, today.minusDays(7), "NO_SHOW");
            reservation(m, today, "RESERVED");
        }
        block(member, today.plusDays(20));
        block(earlier, today.plusDays(3));

        noShowBatch.run();

        assertThat(blockedUntil(member)).isEqualTo(today.plusDays(20));
        assertThat(blockedUntil(earlier)).isEqualTo(today.plusDays(14));
    }

    @Test
    void R17_오늘_노쇼가_없는_회원은_과거_노쇼가_있어도_건드리지_않는다() {
        reservation(member, today.minusDays(7), "NO_SHOW");
        reservation(member, today.minusDays(5), "NO_SHOW");
        long other = data.members(1).get(0);
        reservation(other, today, "RESERVED");

        noShowBatch.run();

        assertThat(blockedUntil(member)).isNull();
    }

    private void at(LocalDate date) {
        at(date, 21, 0);
    }

    private void at(LocalDate date, int hour, int minute) {
        mutableClock.set(date.atTime(hour, minute));
    }

    private long slotOf(LocalDate date) {
        return slots.computeIfAbsent(date, d -> {
            data.saleDate(d);
            return data.slot(d, 11);
        });
    }

    private long reservation(long memberId, LocalDate date, String status) {
        String key = UUID.randomUUID().toString();
        int seq = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation WHERE sale_date = ?", Integer.class, date) + 1;
        jdbcTemplate.update("""
                INSERT INTO reservation (reservation_no, member_id, phone_last4, sale_date, pickup_slot_id, pickup_at,
                                         status, total_price, idempotency_key)
                VALUES (?, ?, '0000', ?, ?, ?, ?, 3500, ?)""",
                "%02d%02d-%04d".formatted(date.getMonthValue(), date.getDayOfMonth(), seq), memberId, date, slotOf(date),
                date.atTime(11, 0), status, key);
        return jdbcTemplate.queryForObject("SELECT id FROM reservation WHERE idempotency_key = ?", Long.class, key);
    }

    private void bulkReservations(List<Long> members, LocalDate date, String status, int perMember) {
        long slot = slotOf(date);
        List<Object[]> rows = new ArrayList<>();
        int seq = 0;
        for (long memberId : members) {
            for (int i = 0; i < perMember; i++) {
                rows.add(new Object[]{"%02d%02d-%04d".formatted(date.getMonthValue(), date.getDayOfMonth(), ++seq), memberId,
                        date, slot, date.atTime(11, 0), status, UUID.randomUUID().toString()});
            }
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO reservation (reservation_no, member_id, phone_last4, sale_date, pickup_slot_id, pickup_at,
                                         status, total_price, idempotency_key)
                VALUES (?, ?, '0000', ?, ?, ?, ?, 3500, ?)""", rows);
    }

    private void block(long memberId, LocalDate until) {
        jdbcTemplate.update("UPDATE member SET reservation_blocked_until = ? WHERE id = ?", until, memberId);
    }

    private String status(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
    }

    private LocalDate blockedUntil(long memberId) {
        return jdbcTemplate.queryForObject("SELECT reservation_blocked_until FROM member WHERE id = ?", LocalDate.class,
                memberId);
    }

    private int historyCount(long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ?",
                Integer.class, id);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, today);
    }
}
