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
import java.util.ArrayList;
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

/**
 * 수령 처리·수령 검색 (A-06, 13-9, N-07·E-09). 판매일 D = 12일 뒤(픽업 11:00, 손님 취소 마감 10:00), D2 = 그다음 날.
 * 예약은 D 전날 12:00 에 API 로 만들고(D·D2 모두 예약 기간), 수령은 시계를 D 09:00 으로 옮겨서 한다(손님 취소도 아직 가능).
 */
@Import(MutableClockConfig.class)
class PickupApiTest extends IntegrationTestSupport {

    private static final long ADMIN_ID = 9_000_001L;
    private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");
    private static final List<String> SAME_LAST4_PHONES = List.of("01060001234", "01070001234");

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
    private LocalDate nextDate;
    private long slot;
    private long nextSlot;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(12);
        nextDate = date.plusDays(1);
        for (LocalDate d : List.of(date, nextDate)) {
            data.saleDate(d);                                        // 오픈 3일 전 10:00, 마감 전날 20:00
            data.stock(d, 3, 100, 50, 0, null);
        }
        slot = data.slot(date, 11);
        nextSlot = data.slot(nextDate, 11);
        now = date.minusDays(1).atTime(12, 0);
        mutableClock.set(now);
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        clean();
    }

    private void clean() {
        data.clean();
        jdbcTemplate.update("DELETE FROM member WHERE phone IN (?, ?)", SAME_LAST4_PHONES.toArray());
    }

    @Test
    void 수령하면_200이고_수령_시각과_이력이_남으며_재고는_DB도_Redis도_그대로() {
        long member = data.memberId();
        long id = reserve(member, date, slot, 2);
        String stockBefore = redis.opsForValue().get(StockKeys.stock(date, 3));
        Map<Object, Object> userQtyBefore = redis.opsForHash().entries(StockKeys.userQty(date, member));
        onPickupDay();

        ResponseEntity<JsonNode> response = pickUp(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("PICKED_UP");
        assertThat(response.getBody().get("pickedUpAt").asText()).isEqualTo(date + "T09:00:00+09:00");
        assertThat(response.getBody().get("canceledAt").isNull()).isTrue();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, picked_up_at FROM reservation WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("PICKED_UP");
        assertThat(row.get("picked_up_at")).isEqualTo(date.atTime(9, 0));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM reservation_history
                WHERE reservation_id = ? AND from_status = 'RESERVED' AND to_status = 'PICKED_UP' AND actor_id = ?""",
                Integer.class, id, ADMIN_ID)).isEqualTo(1);
        assertThat(reserved(date)).isEqualTo(2);
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 3))).isEqualTo(stockBefore).isEqualTo("48");
        assertThat(redis.opsForHash().entries(StockKeys.userQty(date, member))).isEqualTo(userQtyBefore);
    }

    @Test
    void 같은_예약을_동시에_10번_수령하면_1건만_200이고_나머지는_409() throws Exception {
        long id = reserve(data.memberId(), date, slot, 1);
        onPickupDay();
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> pickUp(id));
        }

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(tasks, 10);

        assertThat(statuses(responses)).filteredOn(HttpStatus.OK::equals).hasSize(1);
        List<ResponseEntity<JsonNode>> conflicts = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).toList();
        assertThat(conflicts).hasSize(9).allSatisfy(r -> {
            assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_STATUS_TRANSITION");
            assertThat(r.getBody().get("currentStatus").asText()).isEqualTo("PICKED_UP");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'PICKED_UP'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void 손님_취소와_사장님_수령이_동시에_오면_한쪽만_성공하고_취소가_이긴_예약만_재고가_돌아온다() throws Exception {
        List<Long> members = data.members(20);
        List<Long> ids = members.stream().map(member -> reserve(member, date, slot, 1)).toList();
        onPickupDay();
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            long member = members.get(i);
            long id = ids.get(i);
            tasks.add(() -> patch("/api/v1/reservations/" + id, member, Role.CUSTOMER, Map.of("status", "CANCELED")));
            tasks.add(() -> pickUp(id));
        }

        List<HttpStatus> statuses = statuses(Concurrently.run(tasks, 16));

        for (int i = 0; i < ids.size(); i++) {
            assertThat(statuses.subList(i * 2, i * 2 + 2)).as("예약 %d", ids.get(i))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        }
        int canceled = count("CANCELED");
        int pickedUp = count("PICKED_UP");
        assertThat(canceled + pickedUp).isEqualTo(ids.size());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE sale_date = ? AND canceled_at IS NOT NULL AND picked_up_at IS NOT NULL",
                Integer.class, date)).isZero();
        assertThat(reserved(date)).isEqualTo(pickedUp);                          // 수령한 수량만 남음
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 3))).isEqualTo(String.valueOf(50 - pickedUp));
    }

    @Test
    void 다른_날_예약은_422와_그_판매일_취소된_예약은_409_없는_예약은_404_손님은_403() {
        long member = data.memberId();
        long other = reserve(member, nextDate, nextSlot, 1);
        long canceledId = reserve(member, date, slot, 1);
        long id = reserve(member, date, slot, 1);
        assertThat(patch("/api/v1/reservations/" + canceledId, member, Role.CUSTOMER, Map.of("status", "CANCELED"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        onPickupDay();

        ResponseEntity<JsonNode> otherDay = pickUp(other);
        assertCode(otherDay, HttpStatus.UNPROCESSABLE_ENTITY, "PICKUP_DATE_MISMATCH");
        assertThat(otherDay.getBody().get("saleDate").asText()).isEqualTo(nextDate.toString());
        ResponseEntity<JsonNode> canceled = pickUp(canceledId);
        assertCode(canceled, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        assertThat(canceled.getBody().get("currentStatus").asText()).isEqualTo("CANCELED");
        assertCode(pickUp(999_999_999L), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertCode(patch(adminUrl(id), member, Role.CUSTOMER, Map.of("status", "PICKED_UP")),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertCode(patch(adminUrl(id), ADMIN_ID, Role.ADMIN, Map.of("status", "PICKED_UP", "cancelReason", "수령에는 사유 없음")),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, other))
                .isEqualTo("RESERVED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id))
                .isEqualTo("RESERVED");
    }

    @Test
    void 예약번호_뒷_4자리로_찾고_다른_날_예약은_나오지_않으며_이름과_번호는_마스킹한다() {
        List<Long> sameLast4 = new ArrayList<>();
        for (int i = 0; i < SAME_LAST4_PHONES.size(); i++) {
            jdbcTemplate.update("INSERT INTO member (phone, password_hash, name) VALUES (?, ?, ?)",
                    SAME_LAST4_PHONES.get(i), "$2a$10$" + "x".repeat(53), List.of("김민건", "이건").get(i));
            sameLast4.add(jdbcTemplate.queryForObject("SELECT id FROM member WHERE phone = ?", Long.class,
                    SAME_LAST4_PHONES.get(i)));
        }
        long kim = reserve(sameLast4.get(0), date, slot, 2);
        long lee = reserve(sameLast4.get(1), date, slot, 1);
        reserve(sameLast4.get(0), nextDate, nextSlot, 1);                   // 다른 날, 같은 뒷 4자리
        reserve(data.memberId(), date, slot, 1);                            // 같은 날, 다른 뒷 4자리
        String kimNo = jdbcTemplate.queryForObject("SELECT reservation_no FROM reservation WHERE id = ?", String.class, kim);
        onPickupDay();

        ResponseEntity<JsonNode> byLast4 = search("phoneLast4=1234");                  // 판매일 생략 = 오늘
        ResponseEntity<JsonNode> byNo = search("saleDate=" + date + "&reservationNo=" + kimNo);
        ResponseEntity<JsonNode> otherDayNo = search("saleDate=" + nextDate + "&reservationNo=" + kimNo);

        assertThat(byLast4.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byLast4.getBody()).hasSize(2);
        assertThat(byLast4.getBody().findValuesAsText("reservationId")).containsExactlyInAnyOrder(
                String.valueOf(kim), String.valueOf(lee));
        assertThat(byLast4.getBody().findValuesAsText("memberName")).containsExactlyInAnyOrder("김*건", "이*");
        assertThat(byLast4.getBody().findValuesAsText("phone")).containsExactlyInAnyOrder(
                "010-****-1234", "010-****-1234");
        JsonNode card = byNo.getBody().get(0);
        assertThat(byNo.getBody()).hasSize(1);
        assertThat(card.get("reservationId").asLong()).isEqualTo(kim);
        assertThat(card.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(card.get("totalPrice").asInt()).isPositive();
        assertThat(otherDayNo.getBody()).isEmpty();                                   // 예약번호는 판매일 안에서만 고유
        assertThat(byLast4.toString()).doesNotContain("01060001234", "김민건");

        pickUp(kim);
        assertThat(search("phoneLast4=1234&status=RESERVED").getBody().findValuesAsText("reservationId"))
                .containsExactly(String.valueOf(lee));
    }

    @Test
    void 검색은_예약번호와_뒷_4자리_중_하나만_형식이_틀리면_400_손님은_403() {
        long member = data.memberId();
        onPickupDay();

        assertCode(search(""), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");                       // 둘 다 없음 = A-05 목록
        assertCode(search("reservationNo=1010-0001&phoneLast4=1234"), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(search("reservationNo=10100001"), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(search("phoneLast4=12a4"), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(search("phoneLast4=1234&status=DONE"), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertThat(restTemplate.exchange("/api/v1/admin/reservations?phoneLast4=1234", HttpMethod.GET,
                new HttpEntity<>(headers(member, Role.CUSTOMER)), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void onPickupDay() {
        mutableClock.set(date.atTime(9, 0));
    }

    private long reserve(long memberId, LocalDate saleDate, long slotId, int quantity) {
        synchronized (this) {                       // 같은 손님의 초당 5회 제한에 걸리지 않게 1초씩
            now = now.plusSeconds(1);
            mutableClock.set(now);
        }
        HttpHeaders headers = headers(memberId, Role.CUSTOMER);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("saleDate", saleDate.toString(), "pickupSlotId", slotId,
                "items", List.of(Map.of("breadId", 3, "quantity", quantity)));
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/v1/reservations", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("reservationId").asLong();
    }

    private ResponseEntity<JsonNode> pickUp(long reservationId) {
        return patch(adminUrl(reservationId), ADMIN_ID, Role.ADMIN, Map.of("status", "PICKED_UP"));
    }

    private ResponseEntity<JsonNode> search(String query) {
        return restTemplate.exchange("/api/v1/admin/reservations?" + query, HttpMethod.GET,
                new HttpEntity<>(headers(ADMIN_ID, Role.ADMIN)), JsonNode.class);
    }

    private static String adminUrl(long reservationId) {
        return "/api/v1/admin/reservations/" + reservationId;
    }

    private ResponseEntity<JsonNode> patch(String url, long memberId, Role role, Map<String, Object> body) {
        HttpHeaders headers = headers(memberId, role);
        headers.setContentType(MERGE_PATCH);
        return restTemplate.exchange(url, HttpMethod.PATCH, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private HttpHeaders headers(long memberId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, role, false));
        return headers;
    }

    private static List<HttpStatus> statuses(List<ResponseEntity<JsonNode>> responses) {
        return responses.stream().map(r -> HttpStatus.valueOf(r.getStatusCode().value())).toList();
    }

    private static void assertCode(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }

    private int reserved(LocalDate saleDate) {
        return jdbcTemplate.queryForObject("SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = 3",
                Integer.class, saleDate);
    }

    private int count(String status) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation WHERE sale_date = ? AND status = ?",
                Integer.class, date, status);
    }
}
