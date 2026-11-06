package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.batch.NoShowBatch;
import com.fivexbread.member.Role;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 노쇼 제한이 예약 API 를 막는지와 사장님 해제 (R-17, C-04, A-10). 제한은 실제 배치로 건다.
 * noShowDay 21:00 에 2회째 노쇼 → noShowDay + 14일까지 제한. 판매일 saleDay 는 그 기간 내내 예약이 열려 있다.
 */
@Import(MutableClockConfig.class)
class ReservationBlockApiTest extends IntegrationTestSupport {

    private static final String RESERVE_URL = "/api/v1/reservations";
    private static final String MEMBER_URL = "/api/v1/admin/members/{memberId}";
    private static final String CLEAR_BODY = "{\"reservationBlockedUntil\":null}";
    private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");
    private static final long ADMIN_ID = 9_000_001L;
    private static final long BREAD_ID = 3L;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    NoShowBatch noShowBatch;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    private SaleDateTestData data;
    private LocalDate noShowDay;
    private LocalDate saleDay;
    private long saleSlot;
    private long member;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        noShowDay = data.today().plusDays(50);
        saleDay = noShowDay.plusDays(25);
        data.saleDate(saleDay, noShowDay.minusDays(1).atTime(10, 0), saleDay.minusDays(1).atTime(20, 0), false);
        saleSlot = data.slot(saleDay, 11);
        data.stock(saleDay, BREAD_ID, 100, 50, 0, null);
        member = data.memberId();
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM admin_action_log");
        data.clean();
    }

    @Test
    void 제한된_손님은_422와_blockedUntil_이고_마지막_날까지_막히며_다음_날_성공한다() {
        blockByBatch();
        LocalDate until = noShowDay.plusDays(14);

        mutableClock.set(noShowDay.atTime(21, 1));
        assertBlocked(reserve(), until);

        mutableClock.set(until.atTime(23, 59, 59));
        assertBlocked(reserve(), until);

        mutableClock.set(until.plusDays(1).atStartOfDay());
        assertThat(reserve().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 제한되어도_이미_한_예약은_유지된다() {
        mutableClock.set(noShowDay.atTime(12, 0));
        long id = reserve().getBody().get("reservationId").asLong();

        blockByBatch();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id))
                .isEqualTo("RESERVED");
    }

    @Test
    void 사장님이_해제하면_200이고_이력이_남으며_바로_예약할_수_있다() {
        blockByBatch();

        ResponseEntity<JsonNode> response = clear(member, CLEAR_BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("memberId").asLong()).isEqualTo(member);
        assertThat(response.getBody().get("reservationBlockedUntil").isNull()).isTrue();
        assertThat(response.getBody().toString()).doesNotContain(SaleDateTestData.MEMBER_PHONE);
        assertThat(blockedUntil()).isNull();
        Map<String, Object> log = jdbcTemplate.queryForMap("SELECT admin_id, action, target_type, target_id FROM admin_action_log");
        assertThat(log).containsEntry("admin_id", ADMIN_ID).containsEntry("action", "RESERVATION_BLOCK_CLEARED")
                .containsEntry("target_type", "MEMBER").containsEntry("target_id", String.valueOf(member));
        assertThat(reserve().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 제한이_없는_손님을_해제해도_200이고_이력은_없다() {
        assertThat(clear(member, CLEAR_BODY).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logCount()).isZero();
    }

    @Test
    void 해제가_아닌_본문은_400이고_제한은_그대로다() {
        blockByBatch();
        LocalDate until = blockedUntil();

        for (String body : List.of("{}", "{\"reservationBlockedUntil\":\"2030-01-01\"}", "{\"name\":\"다른 이름\"}",
                "{\"reservationBlockedUntil\":null,\"name\":\"다른 이름\"}")) {
            ResponseEntity<JsonNode> response = clear(member, body);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }

        assertThat(blockedUntil()).isEqualTo(until);
        assertThat(logCount()).isZero();
    }

    @Test
    void 없는_회원과_사장님_계정은_404_손님은_403_다른_형식은_415() {
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name, role) VALUES (?, ?, '사장', 'ADMIN')",
                SaleDateTestData.BULK_PHONE_PREFIX + "999999", "$2a$10$" + "x".repeat(53));
        long adminMember = jdbcTemplate.queryForObject("SELECT id FROM member WHERE phone = ?", Long.class,
                SaleDateTestData.BULK_PHONE_PREFIX + "999999");

        assertThat(clear(Long.MAX_VALUE, CLEAR_BODY).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(clear(adminMember, CLEAR_BODY).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch(member, CLEAR_BODY, jwtProvider.issue(member, Role.CUSTOMER, false), MERGE_PATCH).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patch(member, CLEAR_BODY, adminToken(), MediaType.APPLICATION_JSON).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    private void blockByBatch() {
        for (LocalDate day : List.of(noShowDay.minusDays(7), noShowDay)) {
            if (!day.equals(saleDay)) {
                data.saleDate(day);
            }
            data.reservation(day, data.slot(day, 11), "RESERVED");
            mutableClock.set(day.atTime(21, 0));
            noShowBatch.run();
        }
        assertThat(blockedUntil()).isEqualTo(noShowDay.plusDays(14));
    }

    private void assertBlocked(ResponseEntity<JsonNode> response, LocalDate until) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("RESERVATION_BLOCKED");
        assertThat(response.getBody().get("blockedUntil").asText()).isEqualTo(until.toString());
    }

    private ResponseEntity<JsonNode> reserve() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(member, Role.CUSTOMER, false));
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("saleDate", saleDay.toString(), "pickupSlotId", saleSlot,
                "items", List.of(Map.of("breadId", BREAD_ID, "quantity", 1)));
        return restTemplate.exchange(RESERVE_URL, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> clear(long memberId, String body) {
        return patch(memberId, body, adminToken(), MERGE_PATCH);
    }

    private ResponseEntity<JsonNode> patch(long memberId, String body, String token, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(contentType);
        return restTemplate.exchange(MEMBER_URL, HttpMethod.PATCH, new HttpEntity<>(body, headers), JsonNode.class, memberId);
    }

    private String adminToken() {
        return jwtProvider.issue(ADMIN_ID, Role.ADMIN, false);
    }

    private LocalDate blockedUntil() {
        return jdbcTemplate.queryForObject("SELECT reservation_blocked_until FROM member WHERE id = ?", LocalDate.class, member);
    }

    private int logCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_action_log", Integer.class);
    }
}
