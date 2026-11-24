package com.fivexbread.domain.reservation;

import com.fivexbread.domain.reservation.model.Reservation;
import com.fivexbread.domain.reservation.model.vo.AdminReservationCursor;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.domain.auth.JwtProvider;
import com.fivexbread.domain.member.model.enums.Role;
import com.fivexbread.domain.saledate.SaleDateTestData;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.SqlRecorder;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 판매일 예약 목록·픽업 준비 합계 (A-05). 예약은 테이블에 직접 넣는다.
 * 시간대는 13시 → 9시 → 11시 순서로 만들어 id 순서와 픽업 시각 순서를 일부러 어긋나게 둔다.
 */
@Import(SqlRecorder.class)
class AdminSaleDateReservationApiTest extends IntegrationTestSupport {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    Clock clock;

    private SaleDateTestData data;
    private LocalDate date;
    private Map<Integer, Long> slots;
    private List<Long> members;
    private final Map<LocalDate, Integer> sequences = new HashMap<>();

    private record Resv(long id, String no, int hour, String status) {
    }

    @BeforeEach
    void setUp() {
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        date = data.today().plusDays(13);
        slots = slots(date);
        members = data.members(5);
    }

    @AfterEach
    void tearDown() {
        SqlRecorder.stop();
        data.clean();
        jdbcTemplate.update("DELETE FROM member WHERE id = ?", members.getFirst());   // 탈퇴로 번호를 지운 경우
    }

    @Test
    void 픽업_시각_다음_예약번호_순이고_커서로_끝까지_넘기면_중복_누락이_없다() {
        List<Resv> all = new ArrayList<>();
        int[] hours = {13, 9, 11, 9, 13, 11, 11, 9, 13, 9, 11, 13, 9, 11, 13, 9, 11};   // 예약번호 순서와 시각 순서가 섞인다
        for (int i = 0; i < hours.length; i++) {
            all.add(reservation(date, members.get(i % members.size()), hours[i], "RESERVED"));
        }

        List<Long> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = list(cursor == null ? "?size=5" : "?size=5&cursor=" + cursor).getBody();
            page.get("items").forEach(item -> ids.add(item.get("reservationId").asLong()));
            cursor = page.get("hasNext").asBoolean() ? page.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(4);
        assertThat(ids).isEqualTo(all.stream()
                .sorted(Comparator.comparingInt(Resv::hour).thenComparing(Resv::no))
                .map(Resv::id).toList());
    }

    @Test
    void 항목_빵_이름과_마스킹한_이름_번호와_시간대를_담는다() {
        jdbcTemplate.update("UPDATE member SET name = '김민건' WHERE id = ?", members.getFirst());
        String phone = jdbcTemplate.queryForObject("SELECT phone FROM member WHERE id = ?", String.class,
                members.getFirst());
        Resv resv = reservation(date, members.getFirst(), 11, "RESERVED");
        data.item(resv.id(), 2, 1);
        data.item(resv.id(), 1, 2);

        ResponseEntity<JsonNode> response = list("");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode item = response.getBody().get("items").get(0);
        assertThat(item.get("reservationNo").asText()).isEqualTo(resv.no());
        assertThat(item.get("pickupSlotId").asLong()).isEqualTo(slots.get(11));
        assertThat(item.get("pickupAt").asText()).isEqualTo(date + "T11:00:00+09:00");
        assertThat(item.get("memberName").asText()).isEqualTo("김*건");
        assertThat(item.get("phone").asText()).isEqualTo("010-****-" + phone.substring(7));
        assertThat(item.get("items")).hasSize(2);
        assertThat(item.get("items").get(0).get("breadId").asLong()).isEqualTo(1);
        assertThat(item.get("items").get(0).get("name").asText()).isNotBlank();
        assertThat(item.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(response.getBody().toString()).doesNotContain(phone).doesNotContain("김민건");
    }

    @Test
    void 탈퇴한_손님의_예약은_이름과_번호가_null() {
        reservation(date, members.getFirst(), 11, "PICKED_UP");
        jdbcTemplate.update("UPDATE member SET name = '탈퇴회원', phone = NULL, status = 'WITHDRAWN' WHERE id = ?",
                members.getFirst());

        JsonNode item = list("").getBody().get("items").get(0);

        assertThat(item.get("memberName").isNull()).isTrue();
        assertThat(item.get("phone").isNull()).isTrue();
    }

    @Test
    void 상태와_시간대로_거르고_다른_날의_시간대면_빈_목록() {
        Resv reserved9 = reservation(date, members.get(0), 9, "RESERVED");
        reservation(date, members.get(1), 9, "CANCELED");
        Resv reserved11 = reservation(date, members.get(2), 11, "RESERVED");
        LocalDate otherDate = date.plusDays(1);
        Map<Integer, Long> otherSlots = slots(otherDate);
        reservation(otherDate, members.get(3), 9, "RESERVED");

        assertThat(ids(list("?status=RESERVED").getBody())).containsExactly(reserved9.id(), reserved11.id());
        assertThat(ids(list("?status=RESERVED&pickupSlotId=" + slots.get(9)).getBody()))
                .containsExactly(reserved9.id());
        assertThat(ids(list("?pickupSlotId=" + otherSlots.get(9)).getBody())).isEmpty();
        assertThat(ids(list("?status=&pickupSlotId=&cursor=&size=").getBody())).hasSize(3);
    }

    @Test
    void 쿼리는_본체_1번과_항목_IN_1번() {
        for (int i = 0; i < 12; i++) {
            Resv resv = reservation(date, members.get(i % members.size()), 9 + (i % 3) * 2, "RESERVED");
            data.item(resv.id(), 1, 1);
            data.item(resv.id(), 2, 1);
        }

        SqlRecorder.start();
        JsonNode body = list("?pickupSlotId=" + slots.get(11) + "&status=RESERVED&size=3").getBody();
        List<String> statements = SqlRecorder.matching(SqlRecorder.stop(), "reservation");

        assertThat(body.get("items")).hasSize(3);
        assertThat(statements).hasSize(2);
        assertThat(statements.get(1)).contains("reservation_item");
    }

    /**
     * 인덱스 힌트 없이 옵티마이저가 순서가 맞는 인덱스를 고르고 정렬을 따로 하지 않는지.
     * 실행 계획은 데이터 양·분포로 바뀌므로, 판매일 여러 날에 걸친 예약과 상태가 섞인 분포를 만들고 통계를 갱신한 뒤 본다.
     * 표 형식 EXPLAIN 은 상수 테이블 JOIN 의 정렬을 놓칠 수 있어 FORMAT=TREE 에 Sort 가 없는지도 본다.
     */
    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,true,false", "false,false,true",
            "true,true,true", "true,false,true", "false,true,true"})
    void 목록_쿼리는_힌트_없이_순서가_맞는_인덱스를_쓰고_정렬이_없다(boolean status, boolean slot, boolean cursor) {
        bulk();
        String sql = AdminReservationQueryService.listSql(status, slot, cursor);
        assertThat(sql).doesNotContainIgnoringCase("index (");
        MapSqlParameterSource params = new MapSqlParameterSource("saleDate", date).addValue("status", "RESERVED")
                .addValue("pickupSlotId", slots.get(11)).addValue("cursorPickupAt", date.atTime(9, 0))
                .addValue("cursorNo", Reservation.formatNo(date, 50)).addValue("limit", 101);

        List<Map<String, Object>> plan = explain(sql, params);
        String tree = String.valueOf(explain("FORMAT=TREE " + sql, params).getFirst().values().iterator().next());

        assertThat(row(plan, "r").get("key"))
                .isEqualTo(status ? "ix_resv_date_status_pickup" : "ix_resv_date_pickup");
        assertThat(plan).allSatisfy(row -> assertThat(String.valueOf(row.get("Extra"))).doesNotContain("filesort")
                .doesNotContain("temporary"));
        assertThat(tree).doesNotContain("Sort");
        assertThat(row(plan, "m").get("type")).isEqualTo("eq_ref");
        if (slot) {
            assertThat(row(plan, "s").get("type")).isEqualTo("const");
            assertThat(tree).contains("run only once");
        }
    }

    @Test
    void 합계는_시간대별_빵별_수량과_예약_수_그리고_전체_합계() {
        Resv a = reservation(date, members.get(0), 11, "RESERVED");
        data.item(a.id(), 1, 2);
        data.item(a.id(), 3, 1);
        Resv b = reservation(date, members.get(1), 11, "RESERVED");
        data.item(b.id(), 1, 1);
        Resv c = reservation(date, members.get(2), 9, "RESERVED");
        data.item(c.id(), 3, 4);
        Resv canceled = reservation(date, members.get(3), 9, "CANCELED");
        data.item(canceled.id(), 1, 5);
        Resv pickedUp = reservation(date, members.get(4), 13, "PICKED_UP");
        data.item(pickedUp.id(), 2, 1);

        SqlRecorder.start();
        JsonNode body = get("/api/v1/admin/sale-dates/{d}/pickup-summary", Role.ADMIN, date).getBody();
        List<String> statements = SqlRecorder.matching(SqlRecorder.stop(), "reservation");

        assertThat(statements).hasSize(2);
        assertThat(body.get("status").asText()).isEqualTo("RESERVED");
        JsonNode slotList = body.get("slots");
        assertThat(slotList).hasSize(2);                                       // 9시, 11시. 13시는 RESERVED 가 없다
        assertThat(slotList.get(0).get("pickupSlotId").asLong()).isEqualTo(slots.get(9));
        assertThat(slotList.get(0).get("pickupAt").asText()).isEqualTo(date + "T09:00:00+09:00");
        assertThat(slotList.get(0).get("reservationCount").asInt()).isEqualTo(1);
        assertThat(quantities(slotList.get(0).get("breads"))).containsExactly(Map.entry(3L, 4));
        assertThat(slotList.get(1).get("reservationCount").asInt()).isEqualTo(2);
        assertThat(quantities(slotList.get(1).get("breads"))).containsExactly(Map.entry(1L, 3), Map.entry(3L, 1));
        assertThat(quantities(body.get("totals"))).containsExactly(Map.entry(1L, 3), Map.entry(3L, 5));

        JsonNode pickedUpSummary = get("/api/v1/admin/sale-dates/{d}/pickup-summary?status=PICKED_UP", Role.ADMIN, date)
                .getBody();
        assertThat(quantities(pickedUpSummary.get("totals"))).containsExactly(Map.entry(2L, 1));
    }

    @Test
    void 합계_쿼리는_그날_그_상태만_인덱스로_읽고_예약_수는_인덱스만_읽는다() {
        bulk();
        MapSqlParameterSource params = new MapSqlParameterSource("saleDate", date).addValue("status", "RESERVED");

        Map<String, Object> count = explain(PickupSummaryQueryService.COUNT_SQL, params).getFirst();
        Map<String, Object> quantity = row(explain(PickupSummaryQueryService.QUANTITY_SQL, params), "r");

        assertThat(count.get("key")).isEqualTo("ix_resv_date_status_pickup");
        assertThat(String.valueOf(count.get("Extra"))).contains("Using index").doesNotContain("temporary");
        assertThat(String.valueOf(quantity.get("key"))).startsWith("ix_resv_date_status");   // 그날·그 상태의 예약부터
        assertThat(quantity.get("type")).isEqualTo("ref");
    }

    @Test
    void 손님은_403이고_토큰이_없으면_401() {
        assertThat(get("/api/v1/admin/sale-dates/{d}/reservations", Role.CUSTOMER, date).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/admin/sale-dates/{d}/pickup-summary", Role.CUSTOMER, date).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.getForEntity("/api/v1/admin/sale-dates/{d}/reservations", JsonNode.class, date)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?size=0", "?size=201", "?size=abc", "?status=DONE", "?cursor=not-base64!",
            "?cursor=MjAyNi0xMC0xMHwxMDQy"})
    void 형식이_틀리면_400(String query) {
        ResponseEntity<JsonNode> response = list(query);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void 커서는_되돌리면_같은_값() {
        AdminReservationCursor cursor = new AdminReservationCursor(date.atTime(11, 0), "1010-0427");

        assertThat(AdminReservationCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    // 판매일 3일 × 하루 150건(상태 섞임). 그날 예약이 전체의 일부가 되게 해 판매일 조건의 선택도가 실제와 비슷하게
    private void bulk() {
        List<Long> many = members;
        for (LocalDate day : List.of(date.minusDays(1), date, date.plusDays(2))) {
            Map<Integer, Long> daySlots = day.equals(date) ? slots : slots(day);
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < 150; i++) {
                int hour = new int[]{9, 11, 13}[i % 3];
                String status = i % 5 == 0 ? "CANCELED" : i % 7 == 0 ? "PICKED_UP" : "RESERVED";
                rows.add(new Object[]{no(day), many.get(i % many.size()), day, daySlots.get(hour), day.atTime(hour, 0),
                        status, UUID.randomUUID().toString()});
            }
            jdbcTemplate.batchUpdate("""
                    INSERT INTO reservation (reservation_no, member_id, phone_last4, sale_date, pickup_slot_id, pickup_at,
                                             status, total_price, idempotency_key)
                    VALUES (?, ?, '0000', ?, ?, ?, ?, 3500, ?)""", rows);
        }
        // 항목 1~2개씩. 항목이 비어 있으면 옵티마이저가 빈 reservation_item 부터 읽어 실제와 다른 계획이 나온다
        jdbcTemplate.update("""
                INSERT INTO reservation_item (reservation_id, bread_id, quantity, unit_price)
                SELECT id, 1 + id % 3, 1, 3500 FROM reservation WHERE sale_date BETWEEN ? AND ?""",
                date.minusDays(1), date.plusDays(2));
        jdbcTemplate.update("""
                INSERT INTO reservation_item (reservation_id, bread_id, quantity, unit_price)
                SELECT id, 1 + (id + 1) % 3, 2, 3500 FROM reservation WHERE sale_date BETWEEN ? AND ? AND id % 2 = 0""",
                date.minusDays(1), date.plusDays(2));
        jdbcTemplate.execute("ANALYZE TABLE reservation, reservation_item, member, pickup_slot, bread");
    }

    private Map<Integer, Long> slots(LocalDate day) {
        data.saleDate(day);
        Map<Integer, Long> created = new HashMap<>();
        for (int hour : new int[]{13, 9, 11}) {
            created.put(hour, data.slot(day, hour));
        }
        return created;
    }

    private Resv reservation(LocalDate day, long memberId, int hour, String status) {
        String no = no(day);
        String key = UUID.randomUUID().toString();
        long slotId = jdbcTemplate.queryForObject("SELECT id FROM pickup_slot WHERE sale_date = ? AND pickup_at = ?",
                Long.class, day, day.atTime(hour, 0));
        jdbcTemplate.update("""
                INSERT INTO reservation (reservation_no, member_id, phone_last4, sale_date, pickup_slot_id, pickup_at,
                                         status, total_price, idempotency_key)
                VALUES (?, ?, '0000', ?, ?, ?, ?, 3500, ?)""", no, memberId, day, slotId, day.atTime(hour, 0), status, key);
        long id = jdbcTemplate.queryForObject("SELECT id FROM reservation WHERE idempotency_key = ?", Long.class, key);
        return new Resv(id, no, hour, status);
    }

    private String no(LocalDate day) {
        int seq = sequences.merge(day, 1, Integer::sum);
        return Reservation.formatNo(day, seq);
    }

    private List<Map<String, Object>> explain(String sql, MapSqlParameterSource params) {
        return new NamedParameterJdbcTemplate(jdbcTemplate).queryForList("EXPLAIN " + sql, params);
    }

    private static Map<String, Object> row(List<Map<String, Object>> plan, String table) {
        return plan.stream().filter(row -> table.equals(row.get("table"))).findFirst().orElseThrow();
    }

    private static List<Map.Entry<Long, Integer>> quantities(JsonNode breads) {
        List<Map.Entry<Long, Integer>> entries = new ArrayList<>();
        breads.forEach(bread -> entries.add(Map.entry(bread.get("breadId").asLong(), bread.get("quantity").asInt())));
        return entries;
    }

    private ResponseEntity<JsonNode> list(String query) {
        return get("/api/v1/admin/sale-dates/" + date + "/reservations" + query, Role.ADMIN);
    }

    private ResponseEntity<JsonNode> get(String url, Role role, Object... variables) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(role == Role.ADMIN ? 1 : members.getFirst(), role, false));
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class, variables);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("reservationId").asLong()));
        return ids;
    }
}
