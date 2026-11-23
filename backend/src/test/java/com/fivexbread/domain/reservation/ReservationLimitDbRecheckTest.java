package com.fivexbread.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.domain.auth.JwtProvider;
import com.fivexbread.domain.member.model.enums.Role;
import com.fivexbread.domain.saledate.SaleDateTestData;
import com.fivexbread.domain.saledate.StockKeys;
import com.fivexbread.support.Concurrently;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 예약 v2 한도 DB 재검사 (D1, decisions.md 6장 "예약 v2 한도 DB 재검사").
 * Redis user_qty 를 일부러 실제보다 낮춰 Redis Lua 는 통과시키고, 트랜잭션 안의 DB 검사가 막는지 본다.
 * 시드 빵 2(1인 4개)·3(한도 없음), 날짜는 시드와 겹치지 않는 12일 뒤. 하루 건수 한도는 기본 3건.
 */
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "fivexbread.reservation.mode=redis")
class ReservationLimitDbRecheckTest extends IntegrationTestSupport {

    private static final String URL = "/api/v1/reservations";
    private static final String METRIC = "fivexbread.reservation.limit.db.reject";

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    private SaleDateTestData data;
    private LocalDate date;
    private long slot;
    private long member;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(12);
        data.saleDate(date, date.minusDays(3).atTime(10, 0), date.minusDays(1).atTime(20, 0), false);
        slot = data.slot(date, 11);
        data.stock(date, 2, 100, 50, 0, 4);         // 크로와상: 1인 4개
        data.stock(date, 3, 100, 50, 0, null);      // 한도 없음
        mutableClock.set(date.minusDays(3).atTime(11, 0));
        member = data.memberId();
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
    }

    @Test
    void Redis_user_qty가_낮아도_1인_한도를_넘는_예약은_DB_재검사에서_409이고_Redis는_보상된다() {
        assertThat(post(Map.of(2L, 4)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        redis.opsForHash().put(StockKeys.userQty(date, member), "2", "0");      // Redis 는 0개 예약했다고 믿는다
        double before = rejects(ReservationLimitMetrics.PER_USER_LIMIT);

        ResponseEntity<JsonNode> response = post(Map.of(2L, 1));

        // Redis Lua 의 1인 한도 실패와 같은 형식 (ReservationLimits)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PER_USER_LIMIT_EXCEEDED");
        JsonNode error = response.getBody().get("errors").get(0);
        assertThat(error.get("breadId").asLong()).isEqualTo(2);
        assertThat(error.get("reason").asText()).isEqualTo("PER_USER_LIMIT_EXCEEDED");
        assertThat(error.get("remaining").asInt()).isZero();
        assertThat(response.getBody().get("errors")).hasSize(1);

        assertThat(myQuantity(2)).isEqualTo(4);
        assertThat(reserved(2)).isEqualTo(4);
        assertThat(redisStock(2)).isEqualTo(50 - 4);                              // 보상돼 DB 잔여와 같다
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "2")).isEqualTo("0");
        assertThat(rejects(ReservationLimitMetrics.PER_USER_LIMIT)).isEqualTo(before + 1);
    }

    @Test
    void Redis_건수가_낮아도_하루_건수를_넘는_예약은_DB_재검사에서_422이고_Redis는_보상된다() {
        for (int i = 0; i < 3; i++) {
            assertThat(post(Map.of(3L, 1)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        redis.opsForHash().put(StockKeys.userQty(date, member), "count", "0");
        double before = rejects(ReservationLimitMetrics.DAILY_COUNT);

        ResponseEntity<JsonNode> response = post(Map.of(3L, 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("RESERVATION_COUNT_EXCEEDED");
        assertThat(activeCount()).isEqualTo(3);
        assertThat(reserved(3)).isEqualTo(3);
        assertThat(redisStock(3)).isEqualTo(50 - 3);
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "count")).isEqualTo("0");
        assertThat(rejects(ReservationLimitMetrics.DAILY_COUNT)).isEqualTo(before + 1);
    }

    /**
     * Redis 가 0개로 믿는 상태에서 같은 손님이 2개씩 3건을 동시에 보내면 Redis 는 2건을 통과시킨다(0+2, 2+2).
     * DB 에는 이미 2개가 있어 1건만 들어가야 한다. 회원 행 잠금 뒤 합계를 읽어 두 요청이 같은 합계를 보지 않는다.
     */
    @Test
    void Redis_user_qty가_낮은_상태에서_같은_손님의_동시_요청도_1인_한도를_넘지_않는다() throws Exception {
        assertThat(post(Map.of(2L, 2)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        redis.opsForHash().put(StockKeys.userQty(date, member), "2", "0");
        redis.opsForHash().put(StockKeys.userQty(date, member), "count", "0");   // 하루 건수가 먼저 막지 않게

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(IntStream.range(0, 3)
                .<Callable<ResponseEntity<JsonNode>>>mapToObj(i -> () -> post(Map.of(2L, 2)))
                .toList(), 3);

        assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT))
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.getBody().get("code").asText()).isEqualTo("PER_USER_LIMIT_EXCEEDED"));
        assertThat(myQuantity(2)).isEqualTo(4);
        assertThat(reserved(2)).isEqualTo(4);
        assertThat(redisStock(2)).isEqualTo(50 - 4);
    }

    private ResponseEntity<JsonNode> post(Map<Long, Integer> quantities) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(member, Role.CUSTOMER, false));
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("saleDate", date.toString(), "pickupSlotId", slot,
                "items", quantities.entrySet().stream()
                        .map(e -> Map.of("breadId", e.getKey(), "quantity", e.getValue())).toList());
        return restTemplate.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private double rejects(String reason) {
        return meterRegistry.get(METRIC).tag("reason", reason).counter().count();
    }

    private int myQuantity(long breadId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(i.quantity), 0) FROM reservation r JOIN reservation_item i ON i.reservation_id = r.id
                WHERE r.member_id = ? AND r.sale_date = ? AND r.status <> 'CANCELED' AND i.bread_id = ?""",
                Integer.class, member, date, breadId);
    }

    private int activeCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE member_id = ? AND sale_date = ? AND status <> 'CANCELED'",
                Integer.class, member, date);
    }

    private int reserved(long breadId) {
        return jdbcTemplate.queryForObject("SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = ?",
                Integer.class, date, breadId);
    }

    private int redisStock(long breadId) {
        return Integer.parseInt(redis.opsForValue().get(StockKeys.stock(date, breadId)));
    }
}
