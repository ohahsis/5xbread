package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.LinkedHashMap;
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
import org.springframework.test.context.TestPropertySource;

/**
 * db-only(v1, 재고·한도 검사를 DB로)의 검사가 redis(v2)의 Lua 와 같은 결과를 내는지 (decisions.md 6장 "예약 측정 모드").
 * 1인 한도·하루 건수는 v1 에서 새로 DB 로 보므로 같은 손님의 동시 요청을 함께 확인한다.
 * 빵 1: 마지막 1개, 빵 2: 1인 3개, 빵 3·4·5: 한도 없음. 날짜는 시드와 겹치지 않는 12일 뒤.
 */
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "fivexbread.reservation.mode=db-only")
class ReservationDbOnlyApiTest extends IntegrationTestSupport {

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

    private SaleDateTestData data;
    private LocalDate date;
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
        data.stock(date, 1, 10, 1, 0, null);
        data.stock(date, 2, 100, 50, 0, 3);
        data.stock(date, 3, 100, 50, 0, null);
        data.stock(date, 4, 100, 50, 0, null);
        data.stock(date, 5, 100, 50, 0, null);
        mutableClock.set(openAt.plusHours(1));
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 예약하면_201이고_DB만_줄며_Redis_재고와_1인_누적_키는_만들지_않는다() {
        long member = data.memberId();

        ResponseEntity<JsonNode> response = post(member, UUID.randomUUID(), items(3L, 2, 2L, 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status").asText()).isEqualTo("RESERVED");
        assertThat(reserved(3)).isEqualTo(2);
        assertThat(reserved(2)).isEqualTo(1);
        assertThat(redis.hasKey(StockKeys.stock(date, 3))).isFalse();
        assertThat(redis.hasKey(StockKeys.userQty(date, member))).isFalse();
        assertThat(redis.hasKey(StockKeys.version(date))).isFalse();
    }

    @Test
    void 마지막_1개를_동시에_2건_예약하면_1건_성공_1건_409와_잔여_0() throws Exception {
        List<Long> members = data.members(2);

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(members.stream()
                .<Callable<ResponseEntity<JsonNode>>>map(member -> () -> post(member, UUID.randomUUID(), items(1L, 1)))
                .toList(), 2);

        assertThat(statuses(responses)).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        JsonNode conflict = conflictBody(responses);
        assertThat(conflict.get("code").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(conflict.get("errors").get(0).get("breadId").asLong()).isEqualTo(1);
        assertThat(conflict.get("errors").get(0).get("remaining").asInt()).isZero();
        assertThat(reserved(1)).isEqualTo(1);
    }

    @Test
    void 한도_3개_빵을_같은_손님이_두_탭에서_동시에_2개씩_요청하면_한쪽만_성공하고_PER_USER_LIMIT_EXCEEDED() throws Exception {
        long member = data.memberId();

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(List.<Callable<ResponseEntity<JsonNode>>>of(
                () -> post(member, UUID.randomUUID(), items(2L, 2)),
                () -> post(member, UUID.randomUUID(), items(2L, 2))), 2);

        assertThat(statuses(responses)).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        JsonNode conflict = conflictBody(responses);
        assertThat(conflict.get("code").asText()).isEqualTo("PER_USER_LIMIT_EXCEEDED");
        assertThat(conflict.get("errors").get(0).get("breadId").asLong()).isEqualTo(2);
        assertThat(conflict.get("errors").get(0).get("remaining").asInt()).isEqualTo(1);
        assertThat(reserved(2)).isEqualTo(2);
    }

    @Test
    void 같은_손님이_다른_빵으로_동시에_4건을_보내면_하루_3건까지만_성공하고_나머지는_422() throws Exception {
        long member = data.memberId();

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(List.of(2L, 3L, 4L, 5L).stream()
                .<Callable<ResponseEntity<JsonNode>>>map(bread -> () -> post(member, UUID.randomUUID(), items(bread, 1)))
                .toList(), 4);

        assertThat(statuses(responses)).filteredOn(HttpStatus.CREATED::equals).hasSize(3);
        ResponseEntity<JsonNode> rejected = responses.stream()
                .filter(r -> r.getStatusCode() != HttpStatus.CREATED).findFirst().orElseThrow();
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody().get("code").asText()).isEqualTo("RESERVATION_COUNT_EXCEEDED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation WHERE member_id = ? AND sale_date = ?",
                Integer.class, member, date)).isEqualTo(3);
    }

    @Test
    void 재고_부족과_한도_초과가_섞이면_OUT_OF_STOCK과_빵별_사유_전부이고_아무것도_바뀌지_않는다() {
        long member = data.memberId();
        assertThat(post(member, UUID.randomUUID(), items(2L, 3)).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> response = post(member, UUID.randomUUID(), items(1L, 2, 2L, 1, 3L, 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("OUT_OF_STOCK");
        JsonNode errors = response.getBody().get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).get("breadId").asLong()).isEqualTo(1);
        assertThat(errors.get(0).get("reason").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(errors.get(0).get("remaining").asInt()).isEqualTo(1);
        assertThat(errors.get(1).get("breadId").asLong()).isEqualTo(2);
        assertThat(errors.get(1).get("reason").asText()).isEqualTo("PER_USER_LIMIT_EXCEEDED");
        assertThat(errors.get(1).get("remaining").asInt()).isZero();
        assertThat(reserved(1)).isZero();
        assertThat(reserved(2)).isEqualTo(3);
        assertThat(reserved(3)).isZero();   // 통과한 빵 3의 UPDATE 도 롤백
    }

    @Test
    void 한도만_넘으면_PER_USER_LIMIT_EXCEEDED() {
        long member = data.memberId();

        ResponseEntity<JsonNode> response = post(member, UUID.randomUUID(), items(2L, 4));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PER_USER_LIMIT_EXCEEDED");
        assertThat(response.getBody().get("errors").get(0).get("remaining").asInt()).isEqualTo(3);
        assertThat(reserved(2)).isZero();
    }

    @Test
    void 같은_Idempotency_Key로_다시_보내면_같은_201이고_예약은_1건() {
        long member = data.memberId();
        UUID key = UUID.randomUUID();

        ResponseEntity<JsonNode> first = post(member, key, items(3L, 1));
        ResponseEntity<JsonNode> again = post(member, key, items(3L, 1));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("reservationId")).isEqualTo(first.getBody().get("reservationId"));
        assertThat(reserved(3)).isEqualTo(1);
    }

    private ResponseEntity<JsonNode> post(long memberId, UUID key, Map<Long, Integer> quantities) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, Role.CUSTOMER, false));
        headers.set("Idempotency-Key", key.toString());
        Map<String, Object> body = Map.of("saleDate", date.toString(), "pickupSlotId", slot,
                "items", quantities.entrySet().stream()
                        .map(e -> Map.of("breadId", e.getKey(), "quantity", e.getValue())).toList());
        return restTemplate.exchange("/api/v1/reservations", HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private static Map<Long, Integer> items(Object... pairs) {
        Map<Long, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            items.put((Long) pairs[i], (Integer) pairs[i + 1]);
        }
        return items;
    }

    private static List<HttpStatus> statuses(List<ResponseEntity<JsonNode>> responses) {
        return responses.stream().map(r -> HttpStatus.valueOf(r.getStatusCode().value())).toList();
    }

    private static JsonNode conflictBody(List<ResponseEntity<JsonNode>> responses) {
        return responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow()
                .getBody();
    }

    private int reserved(long breadId) {
        return jdbcTemplate.queryForObject("SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = ?",
                Integer.class, date, breadId);
    }
}
