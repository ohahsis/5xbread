package com.fivexbread.saledate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 손님용 판매일·빵 목록·픽업 시간대 조회 (C-02, 13-4). 모두 공개 API 라 기본 요청은 토큰 없이 보낸다.
 * 시드 빵 1·2·3(판매 중)을 쓰고, 날짜는 시드와 겹치지 않는 15일 뒤. 상태 경계는 MutableClock 으로 시각을 옮겨 확인한다.
 */
@Import(MutableClockConfig.class)
class SaleDateQueryApiTest extends IntegrationTestSupport {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime openAt;
    private LocalDateTime closeAt;

    @BeforeEach
    void setUp() {
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        date = data.today().plusDays(15);
        openAt = date.minusDays(3).atTime(10, 0);
        closeAt = date.minusDays(1).atTime(20, 0);
        data.saleDate(date, openAt, closeAt, false);
        data.stock(date, 1, 100, 60, 30, 4);        // 소금빵: 잔여 30, 한도 4
        data.stock(date, 2, 80, 50, 50, null);      // 크로와상: 매진, 한도 없음
        data.stock(date, 3, 40, 20, 0, 2);          // 잔여 20
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();                               // 예약이 회원을 가리켜 다른 테스트의 회원 삭제를 막지 않게
    }

    @Test
    void 비로그인은_내_예약_수량이_null_로그인하면_취소를_뺀_합계() {
        long slot = data.slot(date, 10);
        long first = data.reservation(date, slot, "RESERVED");
        data.item(first, 1, 2);
        long second = data.reservation(date, slot, "PICKED_UP");
        data.item(second, 1, 1);
        data.item(second, 2, 3);
        long canceled = data.reservation(date, slot, "CANCELED");
        data.item(canceled, 1, 5);                  // 취소는 세지 않음

        ResponseEntity<JsonNode> anonymous = get("/breads", null);
        ResponseEntity<JsonNode> mine = get("/breads", data.memberId());
        ResponseEntity<JsonNode> other = get("/breads", 999_999L);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(anonymous.getHeaders().getCacheControl()).isEqualTo("no-store");
        JsonNode breads = anonymous.getBody().get("breads");
        assertThat(ids(breads)).containsExactly(1L, 3L, 2L);                     // 노출 순서, 매진은 아래로
        assertThat(breads.get(0).get("remaining").asInt()).isEqualTo(30);
        assertThat(breads.get(0).get("perUserLimit").asInt()).isEqualTo(4);
        assertThat(breads.get(2).get("perUserLimit").isNull()).isTrue();         // 한도 없음
        assertThat(breads.get(0).get("name").asText()).isEqualTo("소금빵");
        assertThat(values(breads, "myReservedQty")).containsOnly("null");

        assertThat(values(mine.getBody().get("breads"), "myReservedQty")).containsExactly("3", "0", "3");
        assertThat(values(other.getBody().get("breads"), "myReservedQty")).containsExactly("0", "0", "0");
    }

    @Test
    void 판매_중지된_빵은_목록에서_빠진다() {
        jdbcTemplate.update("UPDATE bread SET active = FALSE WHERE id = 3");
        try {
            assertThat(ids(get("/breads", null).getBody().get("breads"))).containsExactly(1L, 2L);
        } finally {
            jdbcTemplate.update("UPDATE bread SET active = TRUE WHERE id = 3");
        }
    }

    @Test
    void 상태는_오픈_시각부터_OPEN_마감_시각부터_CLOSED() {
        assertStatus(openAt.minusSeconds(1), "NOT_OPEN", "NOT_OPEN", "NOT_OPEN");
        assertStatus(openAt, "OPEN", "AVAILABLE", "SOLD_OUT");
        assertStatus(closeAt.minusSeconds(1), "OPEN", "AVAILABLE", "SOLD_OUT");
        assertStatus(closeAt, "CLOSED", "CLOSED", "CLOSED");
    }

    @Test
    void 휴무일은_HOLIDAY_빵과_픽업_시간대는_빈_목록() {
        LocalDate holiday = date.plusDays(1);
        data.saleDate(holiday, holiday.minusDays(3).atTime(10, 0), holiday.minusDays(1).atTime(20, 0), true);
        mutableClock.set(holiday.minusDays(2).atTime(12, 0));                   // 오픈~마감 사이여도 휴무일

        JsonNode list = list(date, holiday).getBody();
        ResponseEntity<JsonNode> breads = restTemplate.getForEntity(url(holiday) + "/breads", JsonNode.class);

        assertThat(values(list, "status")).containsExactly("OPEN", "HOLIDAY");
        assertThat(restTemplate.getForObject(url(holiday), JsonNode.class).get("status").asText()).isEqualTo("HOLIDAY");
        assertThat(breads.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(breads.getBody().get("status").asText()).isEqualTo("HOLIDAY");
        assertThat(breads.getBody().get("breads")).isEmpty();
        assertThat(restTemplate.getForObject(url(holiday) + "/pickup-slots", JsonNode.class)).isEmpty();
    }

    @Test
    void 기간은_from부터_to까지_최대_31일() {
        LocalDate later = date.plusDays(30);
        data.saleDate(later, later.minusDays(3).atTime(10, 0), later.minusDays(1).atTime(20, 0), false);

        ResponseEntity<JsonNode> thirtyOneDays = list(date, later);
        ResponseEntity<JsonNode> thirtyTwoDays = list(date.minusDays(1), later);

        assertThat(thirtyOneDays.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(values(thirtyOneDays.getBody(), "saleDate")).containsExactly(date.toString(), later.toString());
        assertThat(thirtyOneDays.getBody().get(0).get("openAt").asText()).isEqualTo(openAt.toLocalDate() + "T10:00:00+09:00");
        assertThat(thirtyTwoDays.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(thirtyTwoDays.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(list(later, date).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);         // to < from
        assertThat(restTemplate.getForEntity("/api/v1/sale-dates?from=" + date, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);                                              // to 없음
    }

    @Test
    void 픽업_시간대는_시각순이고_없는_판매일은_404_날짜_형식이_틀리면_400() {
        long eleven = data.slot(date, 11);
        long ten = data.slot(date, 10);

        JsonNode slots = restTemplate.getForObject(url(date) + "/pickup-slots", JsonNode.class);

        assertThat(values(slots, "pickupSlotId")).containsExactly(String.valueOf(ten), String.valueOf(eleven));
        assertThat(slots.get(0).get("pickupAt").asText()).isEqualTo(date + "T10:00:00+09:00");
        LocalDate none = date.plusDays(5);
        for (String path : List.of("", "/breads", "/pickup-slots")) {
            assertThat(restTemplate.getForEntity(url(none) + path, JsonNode.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(restTemplate.getForEntity("/api/v1/sale-dates/2026-13-01" + path, JsonNode.class)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    private void assertStatus(LocalDateTime now, String saleDate, String bread1, String bread2) {
        mutableClock.set(now);
        assertThat(restTemplate.getForObject(url(date), JsonNode.class).get("status").asText()).isEqualTo(saleDate);
        JsonNode breads = restTemplate.getForObject(url(date) + "/breads", JsonNode.class);
        assertThat(breads.get("status").asText()).isEqualTo(saleDate);
        assertThat(values(breads.get("breads"), "status")).containsExactly(bread1, bread1, bread2);  // 1·3 잔여 있음, 2 매진
        assertThat(values(list(date, date).getBody(), "status")).containsExactly(saleDate);
    }

    private ResponseEntity<JsonNode> get(String path, Long memberId) {
        HttpHeaders headers = new HttpHeaders();
        if (memberId != null) {
            headers.setBearerAuth(jwtProvider.issue(memberId, Role.CUSTOMER, false));
        }
        return restTemplate.exchange(url(date) + path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> list(LocalDate from, LocalDate to) {
        return restTemplate.getForEntity("/api/v1/sale-dates?from=" + from + "&to=" + to, JsonNode.class);
    }

    private static String url(LocalDate target) {
        return "/api/v1/sale-dates/" + target;
    }

    private static List<Long> ids(JsonNode breads) {
        List<Long> ids = new ArrayList<>();
        breads.forEach(bread -> ids.add(bread.get("breadId").asLong()));
        return ids;
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.get(field).asText()));
        return values;
    }
}
