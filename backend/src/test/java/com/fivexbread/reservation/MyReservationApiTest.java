package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 내 예약 목록·상세 (C-05, 13-7). 하루 3건 제한을 넘는 건수가 필요해 예약은 테이블에 직접 넣는다.
 * 날짜는 12일 뒤부터, 픽업 11:00(취소 마감 10:00).
 */
@Import(MutableClockConfig.class)
class MyReservationApiTest extends IntegrationTestSupport {

    private static final String LIST = "/api/v1/members/me/reservations";

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    private SaleDateTestData data;
    private LocalDate date;
    private long slot;
    private long member;

    private record Row(LocalDate saleDate, long id) {
    }

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        date = data.today().plusDays(12);
        slot = slot(date);
        member = data.memberId();
    }

    @AfterEach
    void tearDown() {
        statistics().setStatisticsEnabled(false);
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 예약_25건은_20건과_5건으로_나뉘고_합치면_중복_누락_없이_최신순() {
        List<Row> rows = reservations(7, 7, 7, 4);        // 20번째가 세 번째 날짜 중간에 걸려 같은 날짜가 두 페이지로 나뉜다

        JsonNode first = list(member, "").getBody();
        JsonNode second = list(member, "?cursor={c}", first.get("nextCursor").asText()).getBody();

        assertThat(first.get("items")).hasSize(20);
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        assertThat(second.get("items")).hasSize(5);
        assertThat(second.get("hasNext").asBoolean()).isFalse();
        assertThat(second.get("nextCursor").isNull()).isTrue();
        List<Long> ids = new ArrayList<>(ids(first));
        ids.addAll(ids(second));
        assertThat(ids).isEqualTo(rows.stream()
                .sorted(Comparator.comparing(Row::saleDate).thenComparing(Row::id).reversed())
                .map(Row::id).toList());
    }

    @Test
    void 정확히_size_건이면_다음_페이지가_없다() {
        reservations(20);

        JsonNode body = list(member, "").getBody();

        assertThat(body.get("items")).hasSize(20);
        assertThat(body.get("hasNext").asBoolean()).isFalse();
        assertThat(body.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void 예약이_없으면_빈_목록이고_빈_파라미터는_없는_것과_같다() {
        ResponseEntity<JsonNode> response = list(member, "?status=&cursor=&size=");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("items")).isEmpty();
        assertThat(response.getBody().get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void 목록은_항목과_금액과_시각을_담고_남의_예약은_없고_개인_정보라_캐시하지_않는다() {
        long id = data.reservation(date, slot, "RESERVED");
        data.item(id, 2, 1);
        data.item(id, 1, 2);
        long other = data.members(1).getFirst();

        ResponseEntity<JsonNode> response = list(member, "");

        JsonNode item = response.getBody().get("items").get(0);
        assertThat(item.get("reservationId").asLong()).isEqualTo(id);
        assertThat(item.get("status").asText()).isEqualTo("RESERVED");
        assertThat(item.get("pickupAt").asText()).isEqualTo(date + "T11:00:00+09:00");
        assertThat(item.get("cancelDeadline").asText()).isEqualTo(date + "T10:00:00+09:00");
        assertThat(item.get("totalPrice").asInt()).isEqualTo(3500);
        assertThat(item.get("items").findValues("breadId")).extracting(JsonNode::asLong).containsExactly(1L, 2L);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(list(other, "").getBody().get("items")).isEmpty();
    }

    @Test
    void status_필터는_그_상태만_돌려주고_커서로_이어진다() {
        List<Long> canceled = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            canceled.add(data.reservation(date, slot, "CANCELED"));
            data.reservation(date, slot, "RESERVED");
        }

        JsonNode first = list(member, "?status=CANCELED&size=3").getBody();
        JsonNode second = list(member, "?status=CANCELED&size=3&cursor={c}", first.get("nextCursor").asText()).getBody();

        List<Long> ids = new ArrayList<>(ids(first));
        ids.addAll(ids(second));
        assertThat(ids).isEqualTo(canceled.reversed());
        assertThat(second.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void 항목이_있어도_쿼리는_예약_본체_1번과_항목_IN_1번() {
        for (long id : reservations(7, 7, 7, 4).stream().map(Row::id).toList()) {
            data.item(id, 1, 1);
            data.item(id, 2, 1);
        }
        Statistics statistics = statistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        JsonNode body = list(member, "").getBody();

        assertThat(body.get("items").get(19).get("items")).hasSize(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void 상세도_쿼리는_2번() {
        long id = data.reservation(date, slot, "RESERVED");
        data.item(id, 1, 1);
        Statistics statistics = statistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        assertThat(detail(member, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    /** 실행계획은 데이터 양에 따라 바뀌므로, 인덱스를 고정한 쿼리가 네 모양 모두 정렬 없이 인덱스를 타는지 지킨다 */
    @ParameterizedTest
    @CsvSource({"false,false,ix_resv_member_list", "false,true,ix_resv_member_list",
            "true,false,ix_resv_member_status", "true,true,ix_resv_member_status"})
    void 목록_쿼리는_정해진_인덱스를_쓰고_filesort가_없다(boolean status, boolean cursor, String index) {
        reservations(7, 7, 7, 4);

        Map<String, Object> plan = new NamedParameterJdbcTemplate(jdbcTemplate).queryForMap(
                "EXPLAIN " + ReservationListRepository.sql(status, cursor), Map.of("memberId", member,
                        "status", "RESERVED", "saleDate", date.plusDays(2), "id", Long.MAX_VALUE, "limit", 21));

        assertThat(plan.get("key")).isEqualTo(index);
        assertThat(String.valueOf(plan.get("Extra"))).doesNotContain("filesort");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64!", "MjAyNi0xMC0xMA", "MjAyNi0xMC0xMHwxMDQy="})
    void 변조한_커서는_400(String cursor) {
        assertCode(list(member, "?cursor={c}", cursor), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=101", "size=-1", "size=abc", "status=DONE", "status=reserved"})
    void size_범위_밖이거나_status가_enum_값이_아니면_400(String query) {
        assertCode(list(member, "?" + query), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @Test
    void size_1과_100은_허용() {
        assertThat(list(member, "?size=1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list(member, "?size=100").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 로그인하지_않으면_401() {
        assertThat(restTemplate.getForEntity(LIST, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 상세는_항목_금액_픽업과_취소_마감을_오프셋으로_준다() {
        long id = data.reservation(date, slot, "RESERVED");
        data.item(id, 1, 2);
        mutableClock.set(date.minusDays(1).atTime(12, 0));

        JsonNode body = detail(member, id).getBody();

        assertThat(body.get("reservationId").asLong()).isEqualTo(id);
        assertThat(body.get("totalPrice").asInt()).isEqualTo(3500);
        assertThat(body.get("pickupAt").asText()).isEqualTo(date + "T11:00:00+09:00");
        assertThat(body.get("cancelDeadline").asText()).isEqualTo(date + "T10:00:00+09:00");
        assertThat(body.get("cancelable").asBoolean()).isTrue();
        JsonNode item = body.get("items").get(0);
        assertThat(item.get("breadId").asLong()).isEqualTo(1);
        assertThat(item.get("name").asText()).isEqualTo("소금빵");
        assertThat(item.get("quantity").asInt()).isEqualTo(2);
        assertThat(item.get("unitPrice").asInt()).isEqualTo(3500);
    }

    @Test
    void 상세의_cancelable은_마감_정각까지_true이고_1초_뒤는_false() {
        long id = data.reservation(date, slot, "RESERVED");
        LocalDateTime deadline = date.atTime(10, 0);

        mutableClock.set(deadline);
        assertThat(detail(member, id).getBody().get("cancelable").asBoolean()).isTrue();
        mutableClock.set(deadline.plusSeconds(1));
        assertThat(detail(member, id).getBody().get("cancelable").asBoolean()).isFalse();
    }

    @Test
    void 목록의_cancelable도_같은_경계를_따른다() {
        data.reservation(date, slot, "RESERVED");
        LocalDateTime deadline = date.atTime(10, 0);

        mutableClock.set(deadline);
        assertThat(list(member, "").getBody().get("items").get(0).get("cancelable").asBoolean()).isTrue();
        mutableClock.set(deadline.plusSeconds(1));
        assertThat(list(member, "").getBody().get("items").get(0).get("cancelable").asBoolean()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "PICKED_UP", "NO_SHOW"})
    void 끝난_예약은_마감_전이어도_cancelable이_false(String status) {
        long id = data.reservation(date, slot, status);
        mutableClock.set(date.minusDays(1).atTime(12, 0));

        assertThat(detail(member, id).getBody().get("cancelable").asBoolean()).isFalse();
    }

    @Test
    void 남의_예약과_없는_예약은_똑같이_404() {
        long id = data.reservation(date, slot, "RESERVED");
        long other = data.members(1).getFirst();

        assertCode(detail(other, id), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertCode(detail(member, Long.MAX_VALUE), HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    /** 날짜별 건수만큼 예약을 만든다. 날짜를 번갈아 넣어 id 순서와 날짜 순서가 다르게 한다 */
    private List<Row> reservations(int... counts) {
        List<LocalDate> dates = new ArrayList<>();
        List<Long> slots = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            dates.add(i == 0 ? date : date.plusDays(i));
            slots.add(i == 0 ? slot : slot(dates.get(i)));
        }
        List<Row> rows = new ArrayList<>();
        int max = Arrays.stream(counts).max().orElse(0);
        for (int round = 0; round < max; round++) {
            for (int i = 0; i < counts.length; i++) {
                if (round < counts[i]) {
                    rows.add(new Row(dates.get(i), data.reservation(dates.get(i), slots.get(i), "RESERVED")));
                }
            }
        }
        return rows;
    }

    private long slot(LocalDate saleDate) {
        data.saleDate(saleDate);
        return data.slot(saleDate, 11);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private ResponseEntity<JsonNode> list(long memberId, String query, Object... variables) {
        return get(LIST + query, memberId, variables);
    }

    private ResponseEntity<JsonNode> detail(long memberId, long reservationId) {
        return get("/api/v1/reservations/" + reservationId, memberId);
    }

    private ResponseEntity<JsonNode> get(String url, long memberId, Object... variables) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, Role.CUSTOMER, false));
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class, variables);
    }

    private static List<Long> ids(JsonNode page) {
        return page.get("items").findValues("reservationId").stream().map(JsonNode::asLong).toList();
    }

    private static void assertCode(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }
}
