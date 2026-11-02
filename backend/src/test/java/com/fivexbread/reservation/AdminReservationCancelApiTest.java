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
 * 사장님 직권 취소 (A-08, 13-2, E-09). 시드 빵 3, 날짜는 12일 뒤, 픽업 11:00(손님 취소 마감 10:00).
 * 예약은 손님마다 1건씩 API 로 만든다.
 */
@Import(MutableClockConfig.class)
class AdminReservationCancelApiTest extends IntegrationTestSupport {

    private static final long ADMIN_ID = 9_000_001L;
    private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");
    private static final String REASON = "오븐 고장으로 생산 불가";

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
        data.stock(date, 3, 100, 50, 0, null);
        mutableClock.set(openAt.plusHours(1));
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 손님_취소_마감이_지나도_직권_취소할_수_있고_사유와_사장님_id가_남는다() {
        long member = data.memberId();
        long id = reserve(member);
        mutableClock.set(date.atTime(10, 0, 1));                                 // 손님이면 422 인 시각

        ResponseEntity<JsonNode> response = adminCancel(id, "  " + REASON + "\n");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("CANCELED");
        assertThat(response.getBody().get("canceledAt").asText()).isEqualTo(date + "T10:00:01+09:00");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT canceled_by, cancel_reason FROM reservation WHERE id = ?", id);
        assertThat(row.get("canceled_by")).isEqualTo(ADMIN_ID);
        assertThat(row.get("cancel_reason")).isEqualTo(REASON);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM reservation_history
                WHERE reservation_id = ? AND from_status = 'RESERVED' AND to_status = 'CANCELED' AND actor_id = ?""",
                Integer.class, id, ADMIN_ID)).isEqualTo(1);
        assertThat(reserved()).isZero();
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 3))).isEqualTo("50");
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "count")).isEqualTo("0");
    }

    @Test
    void 이미_취소된_예약을_직권_취소하면_409() {
        long member = data.memberId();
        long id = reserve(member);
        assertThat(patch("/api/v1/reservations/" + id, member, Role.CUSTOMER, Map.of("status", "CANCELED"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertCode(adminCancel(id, REASON), HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        assertThat(reserved()).isZero();
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 3))).isEqualTo("50");
    }

    @Test
    void 손님_취소와_직권_취소가_동시에_오면_예약마다_한쪽만_성공한다() throws Exception {
        List<Long> members = data.members(20);
        List<Long> ids = members.stream().map(this::reserve).toList();
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            long member = members.get(i);
            long id = ids.get(i);
            tasks.add(() -> patch("/api/v1/reservations/" + id, member, Role.CUSTOMER, Map.of("status", "CANCELED")));
            tasks.add(() -> adminCancel(id, REASON));
        }

        List<HttpStatus> statuses = Concurrently.run(tasks, 16).stream()
                .map(r -> HttpStatus.valueOf(r.getStatusCode().value())).toList();

        for (int i = 0; i < ids.size(); i++) {
            assertThat(statuses.subList(i * 2, i * 2 + 2)).as("예약 %d", ids.get(i))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        }
        assertThat(reserved()).isZero();
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 3))).isEqualTo("50");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_history WHERE to_status = 'CANCELED' AND reservation_id IN ("
                        + String.join(",", ids.stream().map(String::valueOf).toList()) + ")", Integer.class))
                .isEqualTo(ids.size());
    }

    @Test
    void 사유가_5자_미만이거나_100자_초과이거나_없으면_400_손님이_부르면_403() {
        long member = data.memberId();
        long id = reserve(member);

        ResponseEntity<JsonNode> tooShort = adminCancel(id, "  사유4자  ");          // 앞뒤 공백은 세지 않음
        assertCode(tooShort, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertThat(tooShort.getBody().get("errors").get(0).get("field").asText()).isEqualTo("cancelReason");
        assertCode(adminCancel(id, "가".repeat(101)), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(patch(adminUrl(id), ADMIN_ID, Role.ADMIN, Map.of("status", "CANCELED")),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(patch(adminUrl(id), ADMIN_ID, Role.ADMIN,
                Map.of("status", "CANCELED", "cancelReason", REASON, "memo", "다른 필드")),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(patch(adminUrl(id), ADMIN_ID, Role.ADMIN, Map.of("status", "RESERVED", "cancelReason", REASON)),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(patch(adminUrl(id), member, Role.CUSTOMER, Map.of("status", "CANCELED", "cancelReason", REASON)),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertCode(adminCancel(999_999_999L, REASON), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(adminCancel(id, "가".repeat(100)).getStatusCode()).isEqualTo(HttpStatus.OK);  // 경계 100자는 허용
    }

    private long reserve(long memberId) {
        HttpHeaders headers = headers(memberId, Role.CUSTOMER);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("saleDate", date.toString(), "pickupSlotId", slot,
                "items", List.of(Map.of("breadId", 3, "quantity", 1)));
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/v1/reservations", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("reservationId").asLong();
    }

    private ResponseEntity<JsonNode> adminCancel(long reservationId, String reason) {
        return patch(adminUrl(reservationId), ADMIN_ID, Role.ADMIN,
                Map.of("status", "CANCELED", "cancelReason", reason));
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

    private static void assertCode(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }

    private int reserved() {
        return jdbcTemplate.queryForObject("SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = 3",
                Integer.class, date);
    }
}
