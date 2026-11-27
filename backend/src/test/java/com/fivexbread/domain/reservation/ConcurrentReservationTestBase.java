package com.fivexbread.domain.reservation;

import com.fivexbread.domain.reservation.model.enums.ReservationMode;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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

/**
 * 동시 예약 1,000건의 초과 예약 0건을 두 모드(redis v2 / db-only v1)에서 같은 조건으로 확인한다 (decisions.md 6장 "예약 측정 모드").
 * 모드는 하위 클래스가 각자 @TestPropertySource 로 켠다. 컨텍스트가 모드별로 따로 떠서 다른 테스트의 기본 모드(redis)에 번지지 않는다.
 * 시드 빵 3(한도 없음)을 총량 50으로, 날짜는 시드와 겹치지 않는 12일 뒤.
 */
@Import(MutableClockConfig.class)
abstract class ConcurrentReservationTestBase extends IntegrationTestSupport {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    ReservationProperties properties;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    SaleDateTestData data;
    LocalDate date;
    long slot;

    /** 이 클래스가 켠 모드 */
    abstract ReservationMode mode();

    /** 모드별 Redis 상태 판정. redis 는 잔여 = DB, db-only 는 재고 키를 만들지도 바꾸지도 않음 */
    abstract void assertRedisStock(int reservable, int reserved);

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(12);
        LocalDateTime openAt = date.minusDays(3).atTime(10, 0);
        data.saleDate(date, openAt, date.minusDays(1).atTime(20, 0), false);
        slot = data.slot(date, 11);
        data.stock(date, 3, 100, 50, 0, null);
        mutableClock.set(openAt.plusHours(1));
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 켠_모드로_동작한다() {
        assertThat(properties.mode()).isEqualTo(mode());
    }

    @Test
    void 동시_1000건이어도_예약_수량_합은_총량을_넘지_않는다() throws Exception {
        List<Long> members = data.members(1000);

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(members.stream()
                .<Callable<ResponseEntity<JsonNode>>>map(member -> () -> post(member, Map.of(3L, 1)))
                .toList(), 32);

        List<HttpStatus> statuses = responses.stream().map(r -> HttpStatus.valueOf(r.getStatusCode().value())).toList();
        assertThat(statuses).containsOnly(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(statuses).filteredOn(HttpStatus.CREATED::equals).hasSize(50);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .allSatisfy(r -> assertThat(r.getBody().get("code").asText()).isEqualTo("OUT_OF_STOCK"));
        int sold = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(i.quantity), 0) FROM reservation_item i JOIN reservation r ON r.id = i.reservation_id
                WHERE r.sale_date = ? AND i.bread_id = 3 AND r.status <> 'CANCELED'""", Integer.class, date);
        int reservable = jdbcTemplate.queryForObject(
                "SELECT reservable_qty FROM daily_stock WHERE sale_date = ? AND bread_id = 3", Integer.class, date);
        int reserved = jdbcTemplate.queryForObject(
                "SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = 3", Integer.class, date);
        assertThat(sold).isEqualTo(50).isLessThanOrEqualTo(reservable);
        assertThat(reserved).isEqualTo(sold);
        assertRedisStock(reservable, reserved);
    }

    String stockKey() {
        return StockKeys.stock(date, 3);
    }

    ResponseEntity<JsonNode> post(long memberId, Map<Long, Integer> quantities) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, Role.CUSTOMER, false));
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("saleDate", date.toString(), "pickupSlotId", slot,
                "items", quantities.entrySet().stream()
                        .map(e -> Map.of("breadId", e.getKey(), "quantity", e.getValue())).toList());
        return restTemplate.exchange("/api/v1/reservations", HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class);
    }
}
