package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.saledate.StockKeys;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 예약 생성 (C-04, 13-1, decisions.md 6장). 시드 빵 1·2·3(판매 중), 날짜는 시드와 겹치지 않는 12일 뒤.
 * 현재 시각은 예약 기간 안(오픈 1시간 뒤)으로 고정한다. 요청 제한(초당 5회)이 실제 초에 따라 흔들리지 않게 하려는 것도 있다.
 */
@Import(MutableClockConfig.class)
class ReservationApiTest extends IntegrationTestSupport {

    private static final String URL = "/api/v1/reservations";

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    ReservationIdempotency idempotency;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime openAt;
    private LocalDateTime closeAt;
    private long slot;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(12);
        openAt = date.minusDays(3).atTime(10, 0);
        closeAt = date.minusDays(1).atTime(20, 0);
        data.saleDate(date, openAt, closeAt, false);
        slot = data.slot(date, 11);
        data.stock(date, 1, 10, 1, 0, null);        // 소금빵: 마지막 1개
        data.stock(date, 2, 100, 50, 0, 4);         // 크로와상: 1인 4개
        data.stock(date, 3, 100, 50, 0, null);      // 한도 없음
        mutableClock.set(openAt.plusHours(1));
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 예약하면_201_Location과_예약번호이고_DB와_Redis가_함께_줄어든다() {
        long member = data.memberId();

        ResponseEntity<JsonNode> response = post(member, UUID.randomUUID(), body(Map.of(3L, 2, 2L, 1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode reservation = response.getBody();
        long id = reservation.get("reservationId").asLong();
        assertThat(response.getHeaders().getLocation()).hasToString(URL + "/" + id);
        assertThat(reservation.get("reservationNo").asText())
                .isEqualTo("%02d%02d-0001".formatted(date.getMonthValue(), date.getDayOfMonth()));
        assertThat(reservation.get("status").asText()).isEqualTo("RESERVED");
        assertThat(reservation.get("pickupAt").asText()).isEqualTo(date + "T11:00:00+09:00");
        assertThat(reservation.get("cancelDeadline").asText()).isEqualTo(date + "T10:00:00+09:00");
        assertThat(reservation.get("items").get(0).get("breadId").asLong()).isEqualTo(2);   // breadId 순
        int expectedTotal = 2 * price(3) + price(2);
        assertThat(reservation.get("totalPrice").asInt()).isEqualTo(expectedTotal);

        assertThat(reserved(3)).isEqualTo(2);
        assertThat(redisStock(3)).isEqualTo(48);
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "3")).isEqualTo("2");
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "count")).isEqualTo("1");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation_item WHERE reservation_id = ?",
                Integer.class, id)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT to_status FROM reservation_history WHERE reservation_id = ?",
                String.class, id)).isEqualTo("RESERVED");
    }

    @Test
    void 마지막_1개를_동시에_2건_예약하면_1건_성공_1건_409() throws Exception {
        List<Long> members = data.members(2);

        List<ResponseEntity<JsonNode>> responses = concurrently(members.stream()
                .<Callable<ResponseEntity<JsonNode>>>map(member -> () -> post(member, UUID.randomUUID(), body(Map.of(1L, 1))))
                .toList(), 2);

        assertThat(statuses(responses)).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        JsonNode conflict = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst()
                .orElseThrow().getBody();
        assertThat(conflict.get("code").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(conflict.get("errors").get(0).get("breadId").asLong()).isEqualTo(1);
        assertThat(conflict.get("errors").get(0).get("remaining").asInt()).isZero();
        assertThat(reserved(1)).isEqualTo(1);
        assertThat(redisStock(1)).isZero();
    }

    @Test
    void 동시_1000건이어도_예약_수량_합은_총량을_넘지_않고_Redis_잔여는_DB_계산값과_같다() throws Exception {
        List<Long> members = data.members(1000);

        List<ResponseEntity<JsonNode>> responses = concurrently(members.stream()
                .<Callable<ResponseEntity<JsonNode>>>map(member -> () -> post(member, UUID.randomUUID(), body(Map.of(3L, 1))))
                .toList(), 32);

        assertThat(statuses(responses)).containsOnly(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(statuses(responses)).filteredOn(HttpStatus.CREATED::equals).hasSize(50);
        int sold = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(i.quantity), 0) FROM reservation_item i JOIN reservation r ON r.id = i.reservation_id
                WHERE r.sale_date = ? AND i.bread_id = 3 AND r.status <> 'CANCELED'""", Integer.class, date);
        assertThat(sold).isEqualTo(50).isLessThanOrEqualTo(reservable(3));
        assertThat(reserved(3)).isEqualTo(sold);
        assertThat(redisStock(3)).isEqualTo(reservable(3) - reserved(3));
    }

    @Test
    void 같은_Idempotency_Key로_다시_보내면_같은_201_응답이고_예약은_1건() {
        long member = data.memberId();
        UUID key = UUID.randomUUID();

        ResponseEntity<JsonNode> first = post(member, key, body(Map.of(3L, 2)));
        ResponseEntity<JsonNode> second = post(member, key, body(Map.of(3L, 2)));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation WHERE sale_date = ?", Integer.class,
                date)).isEqualTo(1);
        assertThat(reserved(3)).isEqualTo(2);
        assertThat(redisStock(3)).isEqualTo(48);

        // 같은 키로 다른 본문, 다른 회원이면 422
        ResponseEntity<JsonNode> otherBody = post(member, key, body(Map.of(3L, 1)));
        ResponseEntity<JsonNode> otherMember = post(data.members(1).get(0), key, body(Map.of(3L, 2)));
        assertThat(otherBody.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(otherBody.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(otherMember.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void 실패한_요청의_키는_지워져_같은_키로_다시_시도할_수_있고_처리_중이면_409() {
        long member = data.memberId();
        UUID key = UUID.randomUUID();

        ResponseEntity<JsonNode> failed = post(member, key, body(Map.of(99L, 1)));
        ResponseEntity<JsonNode> retried = post(member, key, body(Map.of(3L, 1)));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 다른 앱이 같은 키·같은 요청을 처리하고 있는 상태
        UUID processing = UUID.randomUUID();
        Map<String, Object> request = body(Map.of(3L, 1));
        String fingerprint = idempotency.fingerprint(member, new ReservationCreateRequest(date, slot,
                List.of(new ReservationCreateRequest.Item(3L, 1))));
        redis.opsForValue().set(ReservationIdempotency.redisKey(processing.toString()), "PROCESSING:" + fingerprint);

        ResponseEntity<JsonNode> inProgress = post(member, processing, request);

        assertThat(inProgress.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inProgress.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
        assertThat(redis.hasKey(ReservationIdempotency.redisKey(processing.toString()))).isTrue();   // 남의 표시는 지우지 않음
    }

    @Test
    void 빵_순서를_반대로_한_요청을_동시에_보내도_데드락_없이_모두_성공한다() throws Exception {
        jdbcTemplate.update("UPDATE daily_stock SET reservable_qty = 100, per_user_limit = NULL WHERE sale_date = ?"
                + " AND bread_id IN (2, 3)", date);
        List<Long> members = data.members(100);
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            long member = members.get(i);
            List<long[]> items = i % 2 == 0 ? List.of(new long[]{2, 1}, new long[]{3, 1})
                    : List.of(new long[]{3, 1}, new long[]{2, 1});           // 요청 본문 순서만 반대
            tasks.add(() -> post(member, UUID.randomUUID(), orderedBody(items)));
        }

        List<ResponseEntity<JsonNode>> responses = concurrently(tasks, 32);

        assertThat(statuses(responses)).containsOnly(HttpStatus.CREATED);
        assertThat(reserved(2)).isEqualTo(100);
        assertThat(reserved(3)).isEqualTo(100);
    }

    @Test
    void 한도를_넘으면_409와_남은_1인_한도() {
        long member = data.memberId();
        assertThat(post(member, UUID.randomUUID(), body(Map.of(2L, 3))).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> response = post(member, UUID.randomUUID(), body(Map.of(2L, 2, 3L, 1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PER_USER_LIMIT_EXCEEDED");
        JsonNode error = response.getBody().get("errors").get(0);
        assertThat(error.get("breadId").asLong()).isEqualTo(2);
        assertThat(error.get("remaining").asInt()).isEqualTo(1);
        assertThat(reserved(3)).isZero();                                        // 함께 담은 빵도 차감하지 않음
        assertThat(redisStock(3)).isEqualTo(50);
    }

    @Test
    void 예약_기간_밖이면_422_오픈_시각_정각부터_가능하고_마감_시각_정각부터_불가() {
        long member = data.memberId();

        mutableClock.set(openAt.minusSeconds(1));
        ResponseEntity<JsonNode> notOpen = post(member, UUID.randomUUID(), body(Map.of(3L, 1)));
        mutableClock.set(closeAt);
        ResponseEntity<JsonNode> closed = post(member, UUID.randomUUID(), body(Map.of(3L, 1)));
        mutableClock.set(openAt);
        ResponseEntity<JsonNode> opened = post(member, UUID.randomUUID(), body(Map.of(3L, 1)));

        assertThat(notOpen.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(notOpen.getBody().get("code").asText()).isEqualTo("RESERVATION_NOT_OPEN");
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(closed.getBody().get("code").asText()).isEqualTo("RESERVATION_CLOSED");
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 이미_2건인_손님이_동시에_5건을_보내면_1건만_성공하고_나머지는_422() throws Exception {
        long member = data.memberId();
        data.item(data.reservation(date, slot, "RESERVED"), 3, 1);
        data.item(data.reservation(date, slot, "RESERVED"), 3, 1);
        data.item(data.reservation(date, slot, "CANCELED"), 3, 1);              // 취소는 세지 않음

        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> post(member, UUID.randomUUID(), body(Map.of(3L, 1))));
        }
        List<ResponseEntity<JsonNode>> responses = concurrently(tasks, 5);

        assertThat(statuses(responses)).filteredOn(HttpStatus.CREATED::equals).hasSize(1);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY)
                .hasSize(4)
                .allSatisfy(r -> assertThat(r.getBody().get("code").asText()).isEqualTo("RESERVATION_COUNT_EXCEEDED"));
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "count")).isEqualTo("3");
    }

    @Test
    void Redis_잔여가_DB보다_많아_DB_조건부_UPDATE가_0행이면_409이고_DB와_Redis는_예약_전_그대로() {
        long member = data.memberId();
        redis.opsForValue().set(StockKeys.stock(date, 1), "10");               // DB 잔여는 1

        ResponseEntity<JsonNode> response = post(member, UUID.randomUUID(), body(Map.of(1L, 2, 3L, 1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(response.getBody().get("errors").get(0).get("breadId").asLong()).isEqualTo(1);
        assertThat(reserved(1)).isZero();
        assertThat(reserved(3)).isZero();                                        // 함께 UPDATE 된 빵도 롤백
        assertThat(redisStock(1)).isEqualTo(10);
        assertThat(redisStock(3)).isEqualTo(50);
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "count")).isEqualTo("0");
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "1")).isEqualTo("0");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation WHERE sale_date = ?", Integer.class,
                date)).isZero();
    }

    @Test
    void 업무_규칙을_어기면_정해진_순서로_404_422() {
        long member = data.memberId();
        LocalDate holiday = date.plusDays(1);
        data.saleDate(holiday, openAt.plusDays(1), closeAt.plusDays(1), true);
        data.saleDate(date.plusDays(2));
        long otherDaySlot = data.slot(date.plusDays(2), 11);                     // 다른 판매일의 시간대

        Map<String, Object> missingDate = body(Map.of(3L, 1));
        missingDate.put("saleDate", date.plusDays(5).toString());
        Map<String, Object> holidayBody = body(Map.of(3L, 1));
        holidayBody.put("saleDate", holiday.toString());
        Map<String, Object> wrongSlot = body(Map.of(3L, 1));
        wrongSlot.put("pickupSlotId", otherDaySlot);

        assertCode(post(member, UUID.randomUUID(), missingDate), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertCode(post(member, UUID.randomUUID(), holidayBody), HttpStatus.UNPROCESSABLE_ENTITY, "RESERVATION_CLOSED");
        assertCode(post(member, UUID.randomUUID(), wrongSlot), HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PICKUP_SLOT");
        ResponseEntity<JsonNode> unavailable = post(member, UUID.randomUUID(), body(Map.of(3L, 1, 4L, 1, 99L, 1)));
        assertCode(unavailable, HttpStatus.UNPROCESSABLE_ENTITY, "BREAD_NOT_AVAILABLE");
        List<Long> breadIds = new ArrayList<>();
        unavailable.getBody().get("errors").forEach(e -> breadIds.add(e.get("breadId").asLong()));
        assertThat(breadIds).containsExactlyInAnyOrder(4L, 99L);                // 이 날 수량 행이 없는 빵·없는 빵
        mutableClock.set(openAt.plusHours(1).plusSeconds(1));                   // 같은 초 6번째 요청은 429 라 1초 넘긴다
        assertCode(post(member, UUID.randomUUID(), body(Map.of(3L, 15, 2L, 4, 1L, 2))),
                HttpStatus.UNPROCESSABLE_ENTITY, "TOTAL_QUANTITY_EXCEEDED");
        jdbcTemplate.update("UPDATE member SET reservation_blocked_until = ? WHERE id = ?", openAt.toLocalDate(),
                member);                                                         // 오늘(오픈일)까지 제한
        assertCode(post(member, UUID.randomUUID(), body(Map.of(3L, 1))), HttpStatus.UNPROCESSABLE_ENTITY,
                "RESERVATION_BLOCKED");
        assertThat(reserved(3)).isZero();
    }

    @Test
    void 형식이_틀리면_400_키가_없으면_428_로그인하지_않으면_401_초당_5회를_넘으면_429() {
        long member = data.memberId();
        Map<String, Object> duplicated = body(Map.of(3L, 1));
        duplicated.put("items", List.of(Map.of("breadId", 3, "quantity", 1), Map.of("breadId", 3, "quantity", 2)));
        Map<String, Object> zero = body(Map.of(3L, 0));

        assertCode(post(member, UUID.randomUUID(), duplicated), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(post(member, UUID.randomUUID(), zero), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(post(member, null, body(Map.of(3L, 1))), HttpStatus.PRECONDITION_REQUIRED, "IDEMPOTENCY_KEY_REQUIRED");
        HttpHeaders badKey = headers(member, null);
        badKey.set("Idempotency-Key", "not-a-uuid");
        assertThat(restTemplate.exchange(URL, HttpMethod.POST, new HttpEntity<>(body(Map.of(3L, 1)), badKey),
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.postForEntity(URL, body(Map.of(3L, 1)), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // 형식 오류(400)·키 없음(428)은 요청 제한 앞에서 끝나 세지 않는다. 같은 초에 5번까지 통과, 6번째 429
        long other = data.members(1).get(0);
        for (int i = 0; i < 5; i++) {
            assertThat(post(other, UUID.randomUUID(), body(Map.of(99L, 1))).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        ResponseEntity<JsonNode> limited = post(other, UUID.randomUUID(), body(Map.of(3L, 1)));
        assertCode(limited, HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS");
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    private ResponseEntity<JsonNode> post(long memberId, UUID key, Map<String, Object> body) {
        return restTemplate.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers(memberId, key)), JsonNode.class);
    }

    private HttpHeaders headers(long memberId, UUID key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, Role.CUSTOMER, false));
        if (key != null) {
            headers.set("Idempotency-Key", key.toString());
        }
        return headers;
    }

    private Map<String, Object> body(Map<Long, Integer> quantities) {
        List<long[]> items = new ArrayList<>();
        quantities.forEach((breadId, quantity) -> items.add(new long[]{breadId, quantity}));
        return orderedBody(items);
    }

    private Map<String, Object> orderedBody(List<long[]> items) {
        Map<String, Object> body = new HashMap<>();
        body.put("saleDate", date.toString());
        body.put("pickupSlotId", slot);
        body.put("items", items.stream().map(item -> Map.of("breadId", item[0], "quantity", item[1])).toList());
        return body;
    }

    private static List<ResponseEntity<JsonNode>> concurrently(List<Callable<ResponseEntity<JsonNode>>> tasks,
                                                               int threads) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<JsonNode>> task : tasks) {
            futures.add(executor.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
        for (Future<ResponseEntity<JsonNode>> future : futures) {
            responses.add(future.get(120, TimeUnit.SECONDS));
        }
        executor.shutdown();
        return responses;
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

    private int price(long breadId) {
        return jdbcTemplate.queryForObject("SELECT price FROM bread WHERE id = ?", Integer.class, breadId);
    }
}
