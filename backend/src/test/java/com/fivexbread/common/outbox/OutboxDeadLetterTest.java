package com.fivexbread.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.domain.auth.JwtProvider;
import com.fivexbread.domain.member.model.enums.Role;
import com.fivexbread.domain.saledate.SaleDateTestData;
import com.fivexbread.domain.saledate.StockKeys;
import com.fivexbread.domain.saledate.StockReconciler;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 아웃박스 실패 한도와 N-05 (decisions.md 6장 "아웃박스 실패 한도"). Redis 를 멈춘 채 취소하면 취소는 DB 로 성공하고
 * 재고 복구 이벤트가 남는다. 폴러가 Redis 시간 초과(2초)로 10번째 실패하면 dead 가 되고, Redis 가 돌아온 뒤에도 더 처리하지 않는다.
 * 그 이벤트가 못 한 재고 복구는 N-05 정기 점검이 5분 간격 두 번 관측으로 맞춘다. 재처리 API 는 없다.
 * 빵 3(총량 5)을 2개 예약 → 취소. 폴러는 스케줄 락 없이 직접 부른다(운영에서는 Redis 장애 중 ShedLock 을 못 잡아 돌지 않는다).
 */
@Import(MutableClockConfig.class)
class OutboxDeadLetterTest extends IntegrationTestSupport {

    private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    OutboxRelay outboxRelay;

    @Autowired
    StockReconciler reconciler;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime now;
    private long slot;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        jdbcTemplate.update("DELETE FROM outbox_event");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(13);
        LocalDateTime openAt = date.minusDays(3).atTime(10, 0);
        data.saleDate(date, openAt, date.atTime(10, 0), false);
        slot = data.slot(date, 11);
        data.stock(date, 3, 100, 5, 0, null);
        now = openAt.plusHours(1);
        mutableClock.set(now);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        mutableClock.reset();
        data.clean();
    }

    @Test
    void Redis를_멈춘_채_10번_실패하면_dead가_되고_복구_뒤에는_N05가_잔여를_맞춘다() {
        long member = data.memberId();
        ResponseEntity<JsonNode> created = reserve(member, 2);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(redisStock()).isEqualTo(3);
        double deadBefore = counter("fivexbread.outbox.dead.events", Map.of());
        double adjustedBefore = counter("fivexbread.reconcile.adjusted.quantity",
                Map.of("source", "scheduled", "direction", "redis_low"));

        long eventId;
        pauseRedis();
        try {
            ResponseEntity<JsonNode> canceled = cancel(member, created.getBody().get("reservationId").asLong());
            assertThat(canceled.getStatusCode()).isEqualTo(HttpStatus.OK);          // 취소는 DB 만으로 성공
            eventId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM outbox_event", Long.class);
            assertThat(attempts(eventId)).isEqualTo(1);                              // 커밋 직후 바로 처리 실패

            mutableClock.set(now.plusMinutes(1));                                    // relayDelay(10초) 지남
            for (int i = 0; i < 9; i++) {
                outboxRelay.publishPending();
            }
        } finally {
            resumeRedis();
        }
        // docker pause 는 연결을 끊지 않아, 클라이언트에서 시간 초과로 끝난 재고 복구 Lua 가 재개 직후 Redis 에서 실행될 수 있다
        // (decisions.md 6장 "장애 실험" 발견 ①). 그러면 실행마다 Redis 가 3 또는 5 가 된다. 같은 연결로 한 번 읽어 밀린 명령을
        // 모두 실행시킨 뒤 "복구를 못 한" 3 으로 되돌려, dead 이벤트의 몫을 N-05 가 맞추는지만 본다
        redisStock();
        redis.opsForValue().set(StockKeys.stock(date, 3), "3");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT attempts, last_error, dead_at, published_at FROM outbox_event WHERE id = ?", eventId);
        assertThat(row.get("attempts")).isEqualTo(10);
        assertThat(row.get("dead_at")).isNotNull();
        assertThat(row.get("published_at")).isNull();
        assertThat((String) row.get("last_error")).startsWith("QueryTimeoutException");
        assertThat(counter("fivexbread.outbox.dead.events", Map.of())).isEqualTo(deadBefore + 1);

        assertThat(outboxRelay.publishPending()).isZero();                           // 복구 뒤에도 dead 는 집지 않는다
        assertThat(redisStock()).isEqualTo(3);                                       // DB 잔여는 5
        assertThat(dbRemaining()).isEqualTo(5);

        assertThat(reconciler.runScheduled()).isZero();                              // 첫 관측
        mutableClock.set(now.plusMinutes(6));
        assertThat(reconciler.runScheduled()).isEqualTo(1);                          // 5분 뒤 같은 차이 → 보정

        assertThat(redisStock()).isEqualTo(5);
        assertThat(counter("fivexbread.reconcile.adjusted.quantity",
                Map.of("source", "scheduled", "direction", "redis_low"))).isEqualTo(adjustedBefore + 2);
    }

    private ResponseEntity<JsonNode> reserve(long memberId, int quantity) {
        HttpHeaders headers = bearer(memberId);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("saleDate", date.toString(), "pickupSlotId", slot,
                "items", List.of(Map.of("breadId", 3, "quantity", quantity)));
        return restTemplate.exchange("/api/v1/reservations", HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> cancel(long memberId, long reservationId) {
        HttpHeaders headers = bearer(memberId);
        headers.setContentType(MERGE_PATCH);
        return restTemplate.exchange("/api/v1/reservations/" + reservationId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "CANCELED"), headers), JsonNode.class);
    }

    private HttpHeaders bearer(long memberId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, Role.CUSTOMER, false));
        return headers;
    }

    private int attempts(long eventId) {
        return jdbcTemplate.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, eventId);
    }

    private int redisStock() {
        return Integer.parseInt(redis.opsForValue().get(StockKeys.stock(date, 3)));
    }

    private int dbRemaining() {
        return jdbcTemplate.queryForObject(
                "SELECT reservable_qty - reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = 3",
                Integer.class, date);
    }

    private double counter(String name, Map<String, String> tags) {
        var search = meterRegistry.get(name);
        tags.forEach(search::tag);
        return search.counter().count();
    }
}
