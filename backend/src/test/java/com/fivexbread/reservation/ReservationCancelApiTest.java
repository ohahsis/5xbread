package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 손님 예약 취소 (C-06, 13-2, R-13·R-15, E-05·E-06). 직권 취소는 AdminReservationCancelApiTest. 시드 빵 1·2·3, 날짜는 12일 뒤, 픽업 11:00(취소 마감 10:00).
 * 예약은 API 로 만든다. 같은 손님의 요청이 초당 5회 제한에 걸리지 않게 예약할 때마다 시계를 1초씩 옮긴다.
 */
@Import(MutableClockConfig.class)
class ReservationCancelApiTest extends IntegrationTestSupport {

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

    @MockitoSpyBean
    ReservationStockStore stockStore;                          // 커밋과 Redis 복구 사이에 user_qty 가 다시 적재되는 상황을 만든다

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime now;
    private LocalDateTime deadline;
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
        deadline = date.atTime(10, 0);                            // 픽업 11:00 − 60분
        data.stock(date, 1, 10, 1, 0, null);        // 소금빵: 1개
        data.stock(date, 2, 100, 50, 0, 4);         // 크로와상: 1인 4개
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
    void 취소하면_200이고_DB와_Redis_재고_1인_누적_건수가_예약_전으로_돌아오며_이력_1행() {
        long member = data.memberId();
        long id = reserve(member, Map.of(2L, 3, 3L, 2));
        assertThat(redisStock(2)).isEqualTo(47);

        ResponseEntity<JsonNode> response = cancel(member, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(response.getBody().get("status").asText()).isEqualTo("CANCELED");
        assertThat(response.getBody().get("canceledAt").asText()).isEqualTo(now.toLocalDate() + "T"
                + now.toLocalTime() + "+09:00");
        assertThat(reserved(2)).isZero();
        assertThat(reserved(3)).isZero();
        assertThat(redisStock(2)).isEqualTo(50);
        assertThat(redisStock(3)).isEqualTo(50);
        assertThat(userQty(member, "2")).isEqualTo("0");
        assertThat(userQty(member, "3")).isEqualTo("0");
        assertThat(userQty(member, "count")).isEqualTo("0");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, canceled_by, cancel_reason FROM reservation WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("CANCELED");
        assertThat(row.get("canceled_by")).isEqualTo(member);
        assertThat(row.get("cancel_reason")).isNull();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM reservation_history
                WHERE reservation_id = ? AND from_status = 'RESERVED' AND to_status = 'CANCELED' AND actor_id = ?""",
                Integer.class, id, member)).isEqualTo(1);
    }

    @Test
    void 픽업_60분_전_정각까지_취소할_수_있고_1초_뒤는_422() {
        List<Long> members = data.members(3);
        List<Long> ids = new ArrayList<>();
        members.forEach(member -> ids.add(reserve(member, Map.of(3L, 1))));

        mutableClock.set(deadline.minusSeconds(1));
        ResponseEntity<JsonNode> before = cancel(members.get(0), ids.get(0));
        mutableClock.set(deadline);
        ResponseEntity<JsonNode> exactly = cancel(members.get(1), ids.get(1));
        mutableClock.set(deadline.plusSeconds(1));
        ResponseEntity<JsonNode> after = cancel(members.get(2), ids.get(2));

        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exactly.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCode(after, HttpStatus.UNPROCESSABLE_ENTITY, "CANCEL_DEADLINE_PASSED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class,
                ids.get(2))).isEqualTo("RESERVED");
        assertThat(reserved(3)).isEqualTo(1);
    }

    @Test
    void 이미_취소한_예약을_다시_취소하면_409이고_재고는_한_번만_돌아온다() {
        long member = data.memberId();
        long id = reserve(member, Map.of(3L, 2));

        assertThat(cancel(member, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCode(cancel(member, id), HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");

        assertThat(reserved(3)).isZero();
        assertThat(redisStock(3)).isEqualTo(50);
        assertThat(userQty(member, "count")).isEqualTo("0");
    }

    @Test
    void 같은_예약을_동시에_10번_취소하면_1건만_200이고_복구도_1번() throws Exception {
        long member = data.memberId();
        long id = reserve(member, Map.of(3L, 2));
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> cancel(member, id));
        }

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(tasks, 10);

        assertThat(statuses(responses)).filteredOn(HttpStatus.OK::equals).hasSize(1);
        assertThat(statuses(responses)).filteredOn(HttpStatus.CONFLICT::equals).hasSize(9);
        assertThat(reserved(3)).isZero();
        assertThat(redisStock(3)).isEqualTo(50);
        assertThat(userQty(member, "3")).isEqualTo("0");
        assertThat(userQty(member, "count")).isEqualTo("0");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'CANCELED'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void 한도까지_예약하고_취소하면_같은_빵을_다시_한도까지_예약할_수_있다() {
        long member = data.memberId();
        long id = reserve(member, Map.of(2L, 4));
        assertThat(post(member, Map.of(2L, 1)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(cancel(member, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(post(member, Map.of(2L, 4)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserved(2)).isEqualTo(4);
    }

    @Test
    void 하루_3건이_찬_손님이_1건_취소하면_1건_더_예약할_수_있고_그다음은_422() {
        long member = data.memberId();
        long first = reserve(member, Map.of(3L, 1));
        reserve(member, Map.of(3L, 1));
        reserve(member, Map.of(3L, 1));
        assertCode(post(member, Map.of(3L, 1)), HttpStatus.UNPROCESSABLE_ENTITY, "RESERVATION_COUNT_EXCEEDED");

        assertThat(cancel(member, first).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(post(member, Map.of(3L, 1)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertCode(post(member, Map.of(3L, 1)), HttpStatus.UNPROCESSABLE_ENTITY, "RESERVATION_COUNT_EXCEEDED");
        assertThat(userQty(member, "count")).isEqualTo("3");
    }

    @Test
    void 매진된_빵을_1개_취소한_뒤_500명이_동시에_예약하면_1건만_성공한다() throws Exception {
        long member = data.memberId();
        long id = reserve(member, Map.of(1L, 1));
        assertThat(redisStock(1)).isZero();
        assertThat(cancel(member, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Long> others = data.members(500);

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(others.stream()
                .<Callable<ResponseEntity<JsonNode>>>map(other -> () -> post(other, Map.of(1L, 1)))
                .toList(), 32);

        assertThat(statuses(responses)).filteredOn(HttpStatus.CREATED::equals).hasSize(1);
        assertThat(statuses(responses)).containsOnly(HttpStatus.CREATED, HttpStatus.CONFLICT);
        int sold = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(i.quantity), 0) FROM reservation_item i JOIN reservation r ON r.id = i.reservation_id
                WHERE r.sale_date = ? AND i.bread_id = 1 AND r.status <> 'CANCELED'""", Integer.class, date);
        assertThat(sold).isEqualTo(1);
        assertThat(reserved(1)).isEqualTo(1);
        assertThat(redisStock(1)).isZero();
    }

    @Test
    void Redis_키가_없을_때_취소하면_DB만_바뀌고_다음_예약은_DB_기준으로_적재한다() {
        long member = data.memberId();
        long id = reserve(member, Map.of(3L, 2));
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();     // Redis 재시작으로 키가 사라짐

        assertThat(cancel(member, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(reserved(3)).isZero();
        assertThat(redis.hasKey(StockKeys.stock(date, 3))).isFalse();            // 없는 키를 0부터 만들지 않음
        assertThat(redis.hasKey(StockKeys.userQty(date, member))).isFalse();
        assertThat(post(member, Map.of(3L, 1)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(redisStock(3)).isEqualTo(reservable(3) - reserved(3)).isEqualTo(49);
        assertThat(userQty(member, "3")).isEqualTo("1");
        assertThat(userQty(member, "count")).isEqualTo("1");
    }

    @Test
    void 커밋과_Redis_복구_사이에_user_qty가_다시_적재돼도_두_번_빼지_않는다() {
        long member = data.memberId();
        long canceled = reserve(member, Map.of(3L, 1));
        reserve(member, Map.of(3L, 1));
        // 취소가 커밋된 뒤 복구 직전에 해시가 사라지고, 같은 손님의 새 예약이 DB 기준(취소 반영됨)으로 다시 적재한다
        doAnswer(invocation -> {
            redis.delete(StockKeys.userQty(date, member));
            assertThat(post(member, Map.of(3L, 1)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            return invocation.callRealMethod();
        }).doCallRealMethod().when(stockStore).releaseOnce(anyLong(), any(), any(), anyLong(), any(), any());

        assertThat(cancel(member, canceled).getStatusCode()).isEqualTo(HttpStatus.OK);

        // DB 기준: 남은 예약 2건(두 번째·새 예약), 빵 3 수량 2. 세대가 바뀌어 복구가 user_qty 를 건너뛰었다
        assertThat(userQty(member, "count")).isEqualTo("2");
        assertThat(userQty(member, "3")).isEqualTo("2");
    }

    @Test
    void 남의_예약은_404_형식이_틀리면_400_415_로그인하지_않으면_401() {
        long owner = data.memberId();
        long id = reserve(owner, Map.of(3L, 1));
        long other = data.members(1).get(0);

        assertCode(cancel(other, id), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertCode(cancel(owner, 999_999_999L), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertCode(patch("/api/v1/reservations/" + id, owner, Role.CUSTOMER,
                Map.of("status", "CANCELED", "cancelReason", "손님은 사유를 못 보냄")), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(patch("/api/v1/reservations/" + id, owner, Role.CUSTOMER, Map.of("status", "PICKED_UP")),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(patch("/api/v1/reservations/" + id, owner, Role.CUSTOMER, Map.of()),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertThat(restTemplate.exchange("/api/v1/reservations/" + id, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "CANCELED"), mergePatchHeaders()), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders json = headers(owner, Role.CUSTOMER);
        json.setContentType(MediaType.APPLICATION_JSON);                        // merge-patch+json 이 아님
        assertThat(restTemplate.exchange("/api/v1/reservations/" + id, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "CANCELED"), json), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id))
                .isEqualTo("RESERVED");
        assertThat(reserved(3)).isEqualTo(1);
    }

    private long reserve(long memberId, Map<Long, Integer> quantities) {
        ResponseEntity<JsonNode> response = post(memberId, quantities);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("reservationId").asLong();
    }

    // 같은 손님의 요청이 초당 5회 제한에 걸리지 않게 예약마다 1초씩 옮긴다 (동시 요청 테스트는 회원이 모두 달라 상관없음)
    private ResponseEntity<JsonNode> post(long memberId, Map<Long, Integer> quantities) {
        synchronized (this) {
            now = now.plusSeconds(1);
            mutableClock.set(now);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("saleDate", date.toString());
        body.put("pickupSlotId", slot);
        body.put("items", quantities.entrySet().stream()
                .map(e -> Map.of("breadId", e.getKey(), "quantity", e.getValue())).toList());
        HttpHeaders headers = headers(memberId, Role.CUSTOMER);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return restTemplate.exchange("/api/v1/reservations", HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> cancel(long memberId, long reservationId) {
        return patch("/api/v1/reservations/" + reservationId, memberId, Role.CUSTOMER, Map.of("status", "CANCELED"));
    }

    private ResponseEntity<JsonNode> patch(String url, long memberId, Role role, Map<String, Object> body) {
        HttpHeaders headers = headers(memberId, role);
        headers.setContentType(MERGE_PATCH);
        return restTemplate.exchange(url, HttpMethod.PATCH, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private HttpHeaders headers(long memberId, Role role) {
        HttpHeaders headers = mergePatchHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, role, false));
        return headers;
    }

    private static HttpHeaders mergePatchHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MERGE_PATCH);
        return headers;
    }

    private static List<HttpStatus> statuses(List<ResponseEntity<JsonNode>> responses) {
        return responses.stream().map(r -> HttpStatus.valueOf(r.getStatusCode().value())).toList();
    }

    private static void assertCode(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }

    private int reserved(long breadId) {
        return jdbcTemplate.queryForObject("SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = ?",
                Integer.class, date, breadId);
    }

    private int reservable(long breadId) {
        return jdbcTemplate.queryForObject("SELECT reservable_qty FROM daily_stock WHERE sale_date = ? AND bread_id = ?",
                Integer.class, date, breadId);
    }

    private int redisStock(long breadId) {
        return Integer.parseInt(redis.opsForValue().get(StockKeys.stock(date, breadId)));
    }

    private String userQty(long memberId, String field) {
        Object value = redis.opsForHash().get(StockKeys.userQty(date, memberId), field);
        return value == null ? null : value.toString();
    }
}
