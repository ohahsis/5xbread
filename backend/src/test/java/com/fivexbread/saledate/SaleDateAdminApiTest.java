package com.fivexbread.saledate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** 판매일 설정 (A-04, 13-8 1단계) */
class SaleDateAdminApiTest extends IntegrationTestSupport {

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

    @BeforeEach
    void setUp() {
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        date = data.today().plusDays(20);
    }

    @Test
    void 손님은_403() {
        ResponseEntity<JsonNode> response = put(date, request(date, false, "10:00", "11:00"), Role.CUSTOMER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("SELECT COUNT(*) FROM sale_date WHERE sale_date = ?")).isZero();
    }

    @Test
    void 처음_설정하면_201과_픽업_시간대_수량_버전은_0() {
        ResponseEntity<JsonNode> response = put(date, request(date, false, "11:00", "10:00"), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/v1/admin/sale-dates/" + date);
        JsonNode body = response.getBody();
        assertThat(body.get("openAt").asText()).isEqualTo(date.minusDays(3) + "T10:00:00+09:00");
        assertThat(texts(body.get("pickupTimes"))).containsExactly("10:00:00", "11:00:00");   // 정렬해서 응답
        assertThat(count("SELECT COUNT(*) FROM pickup_slot WHERE sale_date = ?")).isEqualTo(2);
        assertThat(data.version(date)).isZero();
    }

    @Test
    void 다시_설정하면_200이고_남는_시간대는_id를_유지하며_수량_버전은_그대로() {
        put(date, request(date, false, "10:00", "11:00"), Role.ADMIN);
        long kept = slotId(10);
        jdbcTemplate.update("UPDATE sale_date SET version = 7 WHERE sale_date = ?", date);   // 수량 저장이 7번 있었던 상태

        ResponseEntity<JsonNode> response = put(date, request(date, false, "10:00", "12:30"), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(texts(response.getBody().get("pickupTimes"))).containsExactly("10:00:00", "12:30:00");
        assertThat(slotId(10)).isEqualTo(kept);
        assertThat(data.version(date)).isEqualTo(7);
    }

    @Test
    void 규칙을_어기면_422와_위반_항목_전부() {
        Map<String, Object> request = request(date, false, "07:30", "10:15", "10:00", "10:00");
        request.put("openAt", date.minusDays(3) + "T10:10:00+09:00");             // 00·30분 아님
        request.put("closeAt", date + "T09:30:00+09:00");                         // 첫 픽업 1시간 전보다 늦음

        ResponseEntity<JsonNode> response = put(date, request, Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_SALE_DATE");
        assertThat(reasons(response)).containsExactlyInAnyOrder("OPEN_NOT_HALF_HOUR", "PICKUP_OUT_OF_HOURS",
                "PICKUP_NOT_HALF_HOUR", "PICKUP_DUPLICATE", "CLOSE_TOO_LATE");
        assertThat(count("SELECT COUNT(*) FROM sale_date WHERE sale_date = ?")).isZero();
    }

    @Test
    void 판매일은_내일부터_60일_이내() {
        LocalDate today = data.today();
        LocalDate tooFar = today.plusDays(61);

        assertThat(reasons(put(today, request(today, false, "10:00"), Role.ADMIN))).contains("OUT_OF_RANGE");
        assertThat(reasons(put(tooFar, request(tooFar, false, "10:00"), Role.ADMIN))).contains("OUT_OF_RANGE");
    }

    @Test
    void 오픈_후에는_오픈_시각을_바꿀_수_없고_마감은_늦출_수_없다() {
        LocalDateTime openedAt = LocalDateTime.now(clock).minusHours(1);
        data.saleDate(date, openedAt, date.minusDays(1).atTime(20, 0), false);
        data.slot(date, 10);

        Map<String, Object> request = request(date, false, "10:00");
        request.put("openAt", date.minusDays(3) + "T10:00:00+09:00");
        request.put("closeAt", date.minusDays(1) + "T21:00:00+09:00");

        assertThat(reasons(put(date, request, Role.ADMIN))).contains("OPEN_AT_LOCKED", "CLOSE_AT_POSTPONED");
    }

    @Test
    void 예약이_있는_시간대는_뺄_수_없고_예약이_있는_날은_휴무일로_바꿀_수_없다() {
        data.saleDate(date);
        long reserved = data.slot(date, 10);
        data.slot(date, 11);
        data.reservation(date, reserved);

        assertThat(reasons(put(date, request(date, false, "11:00"), Role.ADMIN))).containsExactly("PICKUP_HAS_RESERVATIONS");
        assertThat(reasons(put(date, request(date, true), Role.ADMIN))).containsExactly("HOLIDAY_HAS_RESERVATIONS");
        assertThat(count("SELECT COUNT(*) FROM pickup_slot WHERE sale_date = ?")).isEqualTo(2);

        // 예약이 없는 시간대는 뺄 수 있다
        assertThat(put(date, request(date, false, "10:00"), Role.ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 휴무일로_바꾸면_시간대와_수량을_지운다_휴무일에는_시간대를_둘_수_없다() {
        data.saleDate(date);
        data.slot(date, 10);
        data.stock(date, 1, 100, 50, 0, null);

        assertThat(reasons(put(date, request(date, true, "10:00"), Role.ADMIN))).containsExactly("HOLIDAY_WITH_PICKUPS");
        ResponseEntity<JsonNode> response = put(date, request(date, true), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("holiday").asBoolean()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM pickup_slot WHERE sale_date = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM daily_stock WHERE sale_date = ?")).isZero();
    }

    private ResponseEntity<JsonNode> put(LocalDate target, Map<String, Object> body, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(1L, role, false));
        return restTemplate.exchange("/api/v1/admin/sale-dates/" + target, HttpMethod.PUT,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    // 오픈 = 3일 전 10:00, 마감 = 전날 20:00
    private static Map<String, Object> request(LocalDate target, boolean holiday, String... pickupTimes) {
        Map<String, Object> body = new HashMap<>();
        body.put("openAt", target.minusDays(3) + "T10:00:00+09:00");
        body.put("closeAt", target.minusDays(1) + "T20:00:00+09:00");
        body.put("holiday", holiday);
        body.put("pickupTimes", List.of(pickupTimes));
        return body;
    }

    private static List<String> reasons(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        return texts(response.getBody().get("errors"), "reason");
    }

    private static List<String> texts(JsonNode array) {
        return texts(array, null);
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(field == null ? node.asText() : node.get(field).asText()));
        return values;
    }

    private long slotId(int hour) {
        return jdbcTemplate.queryForObject("SELECT id FROM pickup_slot WHERE sale_date = ? AND pickup_at = ?",
                Long.class, date, date.atTime(hour, 0));
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, date);
    }
}
