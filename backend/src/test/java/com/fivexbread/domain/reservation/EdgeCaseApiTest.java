package com.fivexbread.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

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
import org.springframework.dao.DataAccessResourceFailureException;
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
 * 정의서 12장 엣지 케이스 중 다른 테스트에 없던 것 (docs/edge-cases.md 에 E번호별 테스트를 모아 둠).
 * 시드 빵 1(마지막 1개), 2(1인 3개), 3(총량 5). 날짜는 13일 뒤, 픽업 11:00·13:00.
 */
@Import(MutableClockConfig.class)
class EdgeCaseApiTest extends IntegrationTestSupport {

    private static final String URL = "/api/v1/reservations";
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
    ReservationTxService txService;                          // E-12: Redis 차감 뒤 DB 저장 실패

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime openAt;
    private LocalDateTime closeAt;
    private LocalDateTime now;
    private long slot;
    private long laterSlot;
    private int originalPrice;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(13);
        openAt = date.minusDays(3).atTime(10, 0);
        closeAt = date.atTime(10, 0);                            // Q-03: 첫 픽업(11:00) 1시간 전. 규칙이 허용하는 가장 늦은 마감
        data.saleDate(date, openAt, closeAt, false);
        slot = data.slot(date, 11);
        laterSlot = data.slot(date, 13);
        data.stock(date, 1, 10, 1, 0, null);
        data.stock(date, 2, 100, 50, 0, 3);
        data.stock(date, 3, 100, 5, 0, null);
        originalPrice = price(3);
        now = openAt.plusHours(1);
        mutableClock.set(now);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("UPDATE bread SET price = ?, active = TRUE WHERE id = 3", originalPrice);
        mutableClock.reset();
        data.clean();
    }

    @Test
    void E02_한도_3개_빵을_같은_손님이_두_탭에서_동시에_2개씩_요청하면_한쪽만_성공하고_다른_쪽은_PER_USER_LIMIT_EXCEEDED() throws Exception {
        long member = data.memberId();

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(List.<Callable<ResponseEntity<JsonNode>>>of(
                () -> post(member, UUID.randomUUID(), Map.of(2L, 2)),
                () -> post(member, UUID.randomUUID(), Map.of(2L, 2))), 2);

        assertThat(responses).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(201, 409);
        JsonNode rejected = responses.stream().filter(r -> r.getStatusCode().value() == 409).findFirst().orElseThrow()
                .getBody();
        assertThat(rejected.get("code").asText()).isEqualTo("PER_USER_LIMIT_EXCEEDED");
        assertThat(rejected.get("errors").get(0).get("remaining").asInt()).isEqualTo(1);    // 남은 1인 한도
        assertThat(reserved(2)).isEqualTo(2);
        assertThat(userQty(member, "2")).isEqualTo("2");
    }

    @Test
    void E03_같은_Idempotency_Key로_동시에_연타해도_예약은_1건이고_나머지는_같은_201_또는_처리_중_409() throws Exception {
        long member = data.memberId();
        UUID key = UUID.randomUUID();
        List<Callable<ResponseEntity<JsonNode>>> taps = new ArrayList<>();
        for (int i = 0; i < 5; i++) {                            // 같은 손님 초당 5회 제한 안에서
            taps.add(() -> post(member, key, Map.of(3L, 1)));
        }

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(taps, 5);

        assertThat(responses).extracting(r -> r.getStatusCode().value()).allMatch(s -> s == 201 || s == 409);
        responses.stream().filter(r -> r.getStatusCode().value() == 409)
                .forEach(r -> assertThat(r.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));
        assertThat(countReservations()).isEqualTo(1);
        assertThat(reserved(3)).isEqualTo(1);
        long id = jdbcTemplate.queryForObject("SELECT id FROM reservation WHERE sale_date = ?", Long.class, date);
        responses.stream().filter(r -> r.getStatusCode().value() == 201)
                .forEach(r -> assertThat(r.getBody().get("reservationId").asLong()).isEqualTo(id));
        tick();
        ResponseEntity<JsonNode> resent = post(member, key, Map.of(3L, 1));     // 끝난 뒤 다시 보내면 같은 201
        assertThat(resent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resent.getBody().get("reservationId").asLong()).isEqualTo(id);
    }

    @Test
    void E04_빵_3종_중_1종이_매진이면_전체_실패하고_나머지_2종은_Redis도_DB도_그대로() {
        assertThat(post(data.members(1).get(0), UUID.randomUUID(), Map.of(1L, 1)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);                     // 빵 1 매진
        long member = data.memberId();
        tick();
        post(member, UUID.randomUUID(), Map.of(3L, 1));          // 이 손님의 user_qty 적재 (비교 기준)
        restTemplate.getForEntity("/api/v1/sale-dates/" + date + "/breads", JsonNode.class);   // 빵 2 재고 키 적재
        int redis2 = redisStock(2);
        int redis3 = redisStock(3);
        tick();

        ResponseEntity<JsonNode> response = post(member, UUID.randomUUID(), Map.of(1L, 1, 2L, 1, 3L, 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(response.getBody().get("errors")).hasSize(1);
        assertThat(response.getBody().get("errors").get(0).get("breadId").asLong()).isEqualTo(1);
        assertThat(redisStock(2)).isEqualTo(redis2).isEqualTo(50);
        assertThat(redisStock(3)).isEqualTo(redis3).isEqualTo(4);
        assertThat(reserved(2)).isZero();
        assertThat(reserved(3)).isEqualTo(1);
        assertThat(userQty(member, "2")).isNull();
        assertThat(userQty(member, "3")).isEqualTo("1");
        assertThat(userQty(member, "count")).isEqualTo("1");
        assertThat(countReservations()).isEqualTo(2);
    }

    @Test
    void E08_마감이_첫_픽업_1시간_전이라_마감_직전_예약도_취소할_수_있고_마감부터는_예약이_안_된다() {
        long member = data.memberId();
        mutableClock.set(closeAt.minusSeconds(1));                // 마감 1초 전, 11:00 픽업

        ResponseEntity<JsonNode> last = postAt(member, slot, Map.of(3L, 1));

        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        LocalDateTime cancelDeadline = java.time.OffsetDateTime.parse(last.getBody().get("cancelDeadline").asText())
                .toLocalDateTime();
        assertThat(cancelDeadline).isAfterOrEqualTo(closeAt);         // 취소 마감(픽업 − 60분) ≥ 예약 마감 > 지금
        assertThat(cancel(member, last.getBody().get("reservationId").asLong()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        mutableClock.set(closeAt);                                     // 마감 정각: 어떤 시간대도 예약 불가
        ResponseEntity<JsonNode> closed = postAt(member, laterSlot, Map.of(3L, 1));
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(closed.getBody().get("code").asText()).isEqualTo("RESERVATION_CLOSED");
    }

    @Test
    void E11_예약_뒤_가격을_바꾸면_기존_예약은_예약_당시_가격이고_새_예약은_새_가격() {
        long member = data.memberId();
        ResponseEntity<JsonNode> before = post(member, UUID.randomUUID(), Map.of(3L, 2));
        long id = before.getBody().get("reservationId").asLong();
        int newPrice = originalPrice + 500;

        assertThat(patchBread(3, Map.of("price", newPrice)).getStatusCode()).isEqualTo(HttpStatus.OK);
        tick();
        ResponseEntity<JsonNode> after = post(member, UUID.randomUUID(), Map.of(3L, 1));

        JsonNode old = detail(member, id);
        assertThat(old.get("totalPrice").asInt()).isEqualTo(2 * originalPrice);
        assertThat(old.get("items").get(0).get("unitPrice").asInt()).isEqualTo(originalPrice);
        assertThat(after.getBody().get("totalPrice").asInt()).isEqualTo(newPrice);
        assertThat(jdbcTemplate.queryForObject("SELECT unit_price FROM reservation_item WHERE reservation_id = ?",
                Integer.class, id)).isEqualTo(originalPrice);
    }

    @Test
    void E12_Redis_차감_뒤_DB_연결이_끊겨_저장이_실패하면_Redis를_되돌리고_503이며_같은_키로_다시_시도할_수_있다() {
        long member = data.memberId();
        UUID key = UUID.randomUUID();
        doThrow(new DataAccessResourceFailureException("DB 연결 끊김")).doCallRealMethod().when(txService)
                .create(any(), anyString(), anyString());

        ResponseEntity<JsonNode> failed = post(member, key, Map.of(3L, 2, 2L, 1));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);   // 저장소 연결 장애 (장애 실험)
        assertThat(failed.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(redisStock(3)).isEqualTo(5);                   // 차감했던 만큼 보상
        assertThat(redisStock(2)).isEqualTo(50);
        assertThat(userQty(member, "3")).isEqualTo("0");
        assertThat(userQty(member, "count")).isEqualTo("0");
        assertThat(countReservations()).isZero();
        assertThat(reserved(3)).isZero();

        tick();
        ResponseEntity<JsonNode> retried = post(member, key, Map.of(3L, 2, 2L, 1));   // Idempotency 키가 풀려 있다
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(redisStock(3)).isEqualTo(3);
    }

    @Test
    void E13_Redis가_멈추면_예약은_503이고_DB는_그대로이며_목록은_DB_기준으로_200이고_복구되면_다시_예약된다() {
        long member = data.memberId();
        post(member, UUID.randomUUID(), Map.of(3L, 1));          // DB 예약 1, Redis 잔여 4
        tick();

        pauseRedis();
        ResponseEntity<JsonNode> reservation;
        ResponseEntity<JsonNode> list;
        try {
            reservation = post(member, UUID.randomUUID(), Map.of(3L, 1));
            list = restTemplate.getForEntity("/api/v1/sale-dates/" + date + "/breads", JsonNode.class);
        } finally {
            resumeRedis();
        }

        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);   // Q-05
        assertThat(reservation.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(countReservations()).isEqualTo(1);
        assertThat(reserved(3)).isEqualTo(1);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);                            // C-02 잔여 읽기
        assertThat(remaining(list.getBody(), 3)).isEqualTo(4);     // DB: 예약 가능 5 − 예약 1
        assertThat(list.getBody().get("version").asLong()).isZero();

        tick();
        assertThat(post(member, UUID.randomUUID(), Map.of(3L, 1)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(redisStock(3)).isEqualTo(3);
    }

    @Test
    void E14_판매_중지된_빵은_수량_행이_있어도_예약하면_422_BREAD_NOT_AVAILABLE이고_목록에서_빠진다() {
        long member = data.memberId();
        assertThat(patchBread(3, Map.of("active", false)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> response = post(member, UUID.randomUUID(), Map.of(3L, 1, 2L, 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("BREAD_NOT_AVAILABLE");
        assertThat(response.getBody().get("errors")).hasSize(1);
        assertThat(response.getBody().get("errors").get(0).get("breadId").asLong()).isEqualTo(3);
        assertThat(reserved(2)).isZero();
        JsonNode list = restTemplate.getForEntity("/api/v1/sale-dates/" + date + "/breads", JsonNode.class).getBody();
        List<Long> ids = new ArrayList<>();
        list.get("breads").forEach(bread -> ids.add(bread.get("breadId").asLong()));
        assertThat(ids).containsExactly(1L, 2L);
    }

    @Test
    void E15_화면보다_잔여가_줄었으면_409_OUT_OF_STOCK과_최신_잔여를_준다() {
        assertThat(post(data.members(1).get(0), UUID.randomUUID(), Map.of(3L, 3)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);                     // 다른 손님이 먼저: 5 → 2
        long member = data.memberId();

        ResponseEntity<JsonNode> response = post(member, UUID.randomUUID(), Map.of(3L, 3));   // 화면에는 5로 보였다

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("OUT_OF_STOCK");
        JsonNode error = response.getBody().get("errors").get(0);
        assertThat(error.get("breadId").asLong()).isEqualTo(3);
        assertThat(error.get("reason").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(error.get("remaining").asInt()).isEqualTo(2).isEqualTo(redisStock(3));
    }

    private void tick() {
        now = now.plusSeconds(1);                                 // 같은 손님 초당 5회 제한
        mutableClock.set(now);
    }

    private ResponseEntity<JsonNode> post(long memberId, UUID key, Map<Long, Integer> quantities) {
        return post(memberId, key, slot, quantities);
    }

    private ResponseEntity<JsonNode> postAt(long memberId, long slotId, Map<Long, Integer> quantities) {
        return post(memberId, UUID.randomUUID(), slotId, quantities);
    }

    private ResponseEntity<JsonNode> post(long memberId, UUID key, long slotId, Map<Long, Integer> quantities) {
        Map<String, Object> body = new HashMap<>();
        body.put("saleDate", date.toString());
        body.put("pickupSlotId", slotId);
        body.put("items", quantities.entrySet().stream()
                .map(e -> Map.of("breadId", e.getKey(), "quantity", e.getValue())).toList());
        HttpHeaders headers = bearer(memberId, Role.CUSTOMER);
        headers.set("Idempotency-Key", key.toString());
        return restTemplate.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> cancel(long memberId, long reservationId) {
        HttpHeaders headers = bearer(memberId, Role.CUSTOMER);
        headers.setContentType(MERGE_PATCH);
        return restTemplate.exchange(URL + "/" + reservationId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "CANCELED"), headers), JsonNode.class);
    }

    private JsonNode detail(long memberId, long reservationId) {
        return restTemplate.exchange(URL + "/" + reservationId, HttpMethod.GET,
                new HttpEntity<>(bearer(memberId, Role.CUSTOMER)), JsonNode.class).getBody();
    }

    private ResponseEntity<JsonNode> patchBread(long breadId, Map<String, Object> body) {
        return restTemplate.exchange("/api/v1/admin/breads/" + breadId, HttpMethod.PATCH,
                new HttpEntity<>(body, bearer(1L, Role.ADMIN)), JsonNode.class);
    }

    private HttpHeaders bearer(long memberId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, role, false));
        return headers;
    }

    private static int remaining(JsonNode list, long breadId) {
        for (JsonNode bread : list.get("breads")) {
            if (bread.get("breadId").asLong() == breadId) {
                return bread.get("remaining").asInt();
            }
        }
        throw new AssertionError("목록에 빵이 없음 breadId=" + breadId);
    }

    private int price(long breadId) {
        return jdbcTemplate.queryForObject("SELECT price FROM bread WHERE id = ?", Integer.class, breadId);
    }

    private int reserved(long breadId) {
        return jdbcTemplate.queryForObject("SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = ?",
                Integer.class, date, breadId);
    }

    private int countReservations() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation WHERE sale_date = ?", Integer.class, date);
    }

    private int redisStock(long breadId) {
        return Integer.parseInt(redis.opsForValue().get(StockKeys.stock(date, breadId)));
    }

    private String userQty(long memberId, String field) {
        Object value = redis.opsForHash().get(StockKeys.userQty(date, memberId), field);
        return value == null ? null : value.toString();
    }
}
