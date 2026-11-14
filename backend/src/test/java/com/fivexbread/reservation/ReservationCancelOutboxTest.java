package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.common.outbox.Outbox;
import com.fivexbread.common.outbox.OutboxRelay;
import com.fivexbread.member.Role;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.saledate.StockKeys;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 취소의 Redis 복구를 아웃박스로 (13-2 4·5단계, decisions.md 6장 "아웃박스 흐름"). 폴러는 락 없이 OutboxRelay 를 직접 부른다.
 * 시드 빵 3(총량 50), 날짜는 12일 뒤. 바로 처리 실패는 Redis 연결 실패 예외로 흉내 낸다.
 */
@Import(MutableClockConfig.class)
class ReservationCancelOutboxTest extends IntegrationTestSupport {

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
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    @Autowired
    OutboxRelay outboxRelay;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoSpyBean
    ReservationStockStore stockStore;                       // 커밋 후 바로 처리가 실패하는 상황

    @MockitoSpyBean
    Outbox outbox;                                          // 아웃박스 INSERT 뒤 업무 트랜잭션이 실패하는 상황

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime now;
    private long slot;

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
        now = openAt.plusHours(1);
        mutableClock.set(now);
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 취소하면_같은_트랜잭션에_아웃박스_행이_생기고_커밋_후_바로_처리돼_published_at이_채워진다() throws Exception {
        long member = data.memberId();
        long id = reserve(member, 3);
        String generation = userQty(member, "gen");

        assertThat(cancel(member, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT event_type, payload, published_at FROM outbox_event");
        assertThat(row.get("event_type")).isEqualTo("STOCK_RELEASE");
        assertThat(row.get("published_at")).isNotNull();
        JsonNode payload = objectMapper.readTree(row.get("payload").toString());
        assertThat(payload.get("reservationId").asLong()).isEqualTo(id);
        assertThat(payload.get("saleDate").asText()).isEqualTo(date.toString());
        assertThat(payload.get("memberId").asLong()).isEqualTo(member);
        assertThat(payload.get("quantities").get("3").asInt()).isEqualTo(3);
        assertThat(payload.get("generation").asText()).isEqualTo(generation);     // 커밋 전에 읽은 세대
        assertThat(redisStock()).isEqualTo(50);
        assertThat(userQty(member, "3")).isEqualTo("0");
        assertThat(userQty(member, "count")).isEqualTo("0");
    }

    @Test
    void 바로_처리가_Redis_장애로_실패해도_취소는_200이고_폴러가_relayDelay_뒤에_한_번만_적용한다() {
        long member = data.memberId();
        long id = reserve(member, 3);
        doThrow(new RedisConnectionFailureException("Redis 일시 장애")).doCallRealMethod().when(stockStore)
                .releaseOnce(anyLong(), any(), any(), anyLong(), any(), any());

        assertThat(cancel(member, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reserved()).isZero();
        assertThat(redisStock()).isEqualTo(47);                                   // 아직 복구 전
        assertThat(pending()).isEqualTo(1);

        assertThat(outboxRelay.publishPending()).isZero();                        // 10초가 지나지 않은 행은 바로 처리 경로의 몫
        mutableClock.set(now.plusSeconds(10));
        assertThat(outboxRelay.publishPending()).isEqualTo(1);

        assertThat(pending()).isZero();
        assertThat(redisStock()).isEqualTo(50);
        assertThat(userQty(member, "3")).isEqualTo("0");
        assertThat(userQty(member, "count")).isEqualTo("0");

        assertThat(outboxRelay.publishPending()).isZero();                        // 다시 돌려도 바뀌지 않음
        assertThat(redisStock()).isEqualTo(50);
    }

    @Test
    void Redis에_적용한_뒤_published_at을_적기_전에_죽어도_다시_처리할_때_재고와_user_qty는_한_번만_바뀐다() {
        long member = data.memberId();
        long id = reserve(member, 3);
        reserve(member, 2);
        assertThat(cancel(member, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(redisStock()).isEqualTo(48);
        // Redis 적용은 됐지만 published_at UPDATE 전에 앱이 죽은 상황
        jdbcTemplate.update("UPDATE outbox_event SET published_at = NULL");

        mutableClock.set(now.plusSeconds(10));
        assertThat(outboxRelay.publishPending()).isEqualTo(1);                    // 발행 표시만 채운다
        long eventId = jdbcTemplate.queryForObject("SELECT id FROM outbox_event", Long.class);
        outboxRelay.publish(eventId);                                              // 바로 처리 경로가 늦게 다시 와도

        assertThat(pending()).isZero();
        assertThat(redisStock()).isEqualTo(48);
        assertThat(userQty(member, "3")).isEqualTo("2");
        assertThat(userQty(member, "count")).isEqualTo("1");
        assertThat(redis.hasKey(StockKeys.applied(date, eventId))).isTrue();
        assertThat(redis.getExpire(StockKeys.applied(date, eventId))).isPositive();  // 판매일 다음 날 0시에 만료
    }

    @Test
    void 처리가_늦어진_사이_재고_키가_DB_기준으로_다시_적재되면_재고는_더하지_않는다() {
        long member = data.memberId();
        long other = data.members(1).get(0);
        long id = reserve(member, 3);
        doThrow(new RedisConnectionFailureException("Redis 일시 장애")).doCallRealMethod().when(stockStore)
                .releaseOnce(anyLong(), any(), any(), anyLong(), any(), any());
        assertThat(cancel(member, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Redis 가 데이터를 잃고, 다른 손님의 예약이 DB 기준(취소가 이미 반영된 50)으로 다시 적재한 뒤 1개 차감
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        reserve(other, 1);
        assertThat(redisStock()).isEqualTo(49);

        mutableClock.set(now.plusSeconds(10));
        assertThat(outboxRelay.publishPending()).isEqualTo(1);

        assertThat(redisStock()).isEqualTo(reservable() - reserved()).isEqualTo(49);   // 51 이 되지 않음
        assertThat(pending()).isZero();
    }

    @Test
    void 아웃박스_INSERT_뒤_업무_트랜잭션이_롤백되면_아웃박스_행도_없고_예약과_Redis는_그대로다() {
        long member = data.memberId();
        long id = reserve(member, 3);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("INSERT 뒤 실패");
        }).when(outbox).append(anyString(), any());

        assertThat(cancel(member, id).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id))
                .isEqualTo("RESERVED");
        assertThat(reserved()).isEqualTo(3);
        assertThat(redisStock()).isEqualTo(47);
    }

    private long reserve(long memberId, int quantity) {
        now = now.plusSeconds(1);                                                  // 같은 손님 초당 5회 제한
        mutableClock.set(now);
        Map<String, Object> body = new HashMap<>();
        body.put("saleDate", date.toString());
        body.put("pickupSlotId", slot);
        body.put("items", java.util.List.of(Map.of("breadId", 3, "quantity", quantity)));
        HttpHeaders headers = headers(memberId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/v1/reservations", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("reservationId").asLong();
    }

    private ResponseEntity<JsonNode> cancel(long memberId, long reservationId) {
        HttpHeaders headers = headers(memberId);
        headers.setContentType(MERGE_PATCH);
        return restTemplate.exchange("/api/v1/reservations/" + reservationId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "CANCELED"), headers), JsonNode.class);
    }

    private HttpHeaders headers(long memberId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, Role.CUSTOMER, false));
        return headers;
    }

    private int pending() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Integer.class);
    }

    private int reserved() {
        return jdbcTemplate.queryForObject("SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = 3",
                Integer.class, date);
    }

    private int reservable() {
        return jdbcTemplate.queryForObject("SELECT reservable_qty FROM daily_stock WHERE sale_date = ? AND bread_id = 3",
                Integer.class, date);
    }

    private int redisStock() {
        return Integer.parseInt(redis.opsForValue().get(StockKeys.stock(date, 3)));
    }

    private String userQty(long memberId, String field) {
        Object value = redis.opsForHash().get(StockKeys.userQty(date, memberId), field);
        return value == null ? null : value.toString();
    }
}
