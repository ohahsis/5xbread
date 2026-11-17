package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.support.IntegrationTestSupport;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** 사장님 예약 상세 GET /admin/reservations/{id}. 예약·이력은 테이블에 직접 넣는다 */
class AdminReservationDetailApiTest extends IntegrationTestSupport {

    private static final String ADMIN_PHONE = "01077779999";

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
    private long slot;
    private long member;
    private long admin;

    @BeforeEach
    void setUp() {
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        jdbcTemplate.update("DELETE FROM member WHERE phone = ?", ADMIN_PHONE);
        date = data.today().plusDays(12);
        data.saleDate(date);
        slot = data.slot(date, 11);
        member = data.memberId();
        jdbcTemplate.update("UPDATE member SET name = '김민건' WHERE id = ?", member);
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name, role) VALUES (?, ?, '사장님', 'ADMIN')",
                ADMIN_PHONE, "$2a$10$" + "x".repeat(53));
        admin = jdbcTemplate.queryForObject("SELECT id FROM member WHERE phone = ?", Long.class, ADMIN_PHONE);
    }

    @AfterEach
    void tearDown() {
        data.clean();
        jdbcTemplate.update("DELETE FROM member WHERE id = ?", member);           // 탈퇴로 번호를 지운 경우 clean 이 못 찾는다
        jdbcTemplate.update("DELETE FROM member WHERE phone = ?", ADMIN_PHONE);
    }

    @Test
    void 손님_상세_내용과_마스킹한_회원과_시간순_이력을_담는다() {
        long id = data.reservation(date, slot, "CANCELED");
        data.item(id, 2, 1);
        data.item(id, 1, 2);
        LocalDateTime created = date.minusDays(2).atTime(10, 0, 1);
        LocalDateTime canceled = date.minusDays(1).atTime(15, 20);
        jdbcTemplate.update("UPDATE reservation SET created_at = ?, canceled_at = ?, canceled_by = ?, cancel_reason = ? "
                + "WHERE id = ?", created, canceled, admin, "재료가 떨어졌습니다", id);
        history(id, null, "RESERVED", member, created);
        history(id, "RESERVED", "CANCELED", admin, canceled);

        ResponseEntity<JsonNode> response = detail(id, Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("reservationId").asLong()).isEqualTo(id);
        assertThat(body.get("status").asText()).isEqualTo("CANCELED");
        assertThat(body.get("pickupSlotId").asLong()).isEqualTo(slot);
        assertThat(body.get("pickupAt").asText()).isEqualTo(date + "T11:00:00+09:00");
        assertThat(body.get("cancelDeadline").asText()).isEqualTo(date + "T10:00:00+09:00");
        assertThat(body.get("cancelable").asBoolean()).isFalse();
        assertThat(body.get("canceledAt").asText()).isEqualTo(date.minusDays(1) + "T15:20:00+09:00");
        assertThat(body.get("cancelReason").asText()).isEqualTo("재료가 떨어졌습니다");
        assertThat(body.get("pickedUpAt").isNull()).isTrue();
        assertThat(body.get("createdAt").asText()).isEqualTo(date.minusDays(2) + "T10:00:01+09:00");
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("breadId").asLong()).isEqualTo(1);
        assertThat(body.get("totalPrice").asInt()).isEqualTo(3500);

        JsonNode memberNode = body.get("member");
        assertThat(memberNode.get("memberId").asLong()).isEqualTo(member);
        assertThat(memberNode.get("name").asText()).isEqualTo("김*건");
        assertThat(memberNode.get("phone").asText()).isEqualTo("010-****-0000");
        assertThat(memberNode.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.toString()).doesNotContain(SaleDateTestData.MEMBER_PHONE).doesNotContain("김민건");

        JsonNode history = body.get("history");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("fromStatus").isNull()).isTrue();
        assertThat(history.get(0).get("toStatus").asText()).isEqualTo("RESERVED");
        assertThat(history.get(0).get("actorType").asText()).isEqualTo("CUSTOMER");
        assertThat(history.get(0).get("changedAt").asText()).isEqualTo(date.minusDays(2) + "T10:00:01+09:00");
        assertThat(history.get(1).get("fromStatus").asText()).isEqualTo("RESERVED");
        assertThat(history.get(1).get("toStatus").asText()).isEqualTo("CANCELED");
        assertThat(history.get(1).get("actorType").asText()).isEqualTo("ADMIN");
        assertThat(history.get(1).has("actorId")).isFalse();                      // 사장님 id 는 내보내지 않는다
    }

    @Test
    void 노쇼_배치가_바꾼_이력은_SYSTEM이고_RESERVED는_손님_기준_취소_가능() {
        long noShow = data.reservation(date, slot, "NO_SHOW");
        history(noShow, null, "RESERVED", member, date.minusDays(2).atTime(10, 0));
        history(noShow, "RESERVED", "NO_SHOW", null, date.atTime(21, 0));
        long reserved = data.reservation(date, slot, "RESERVED");

        JsonNode noShowBody = detail(noShow, Role.ADMIN).getBody();
        JsonNode reservedBody = detail(reserved, Role.ADMIN).getBody();

        assertThat(actorTypes(noShowBody)).containsExactly("CUSTOMER", "SYSTEM");
        assertThat(reservedBody.get("cancelable").asBoolean()).isTrue();          // 판매일이 12일 뒤
        assertThat(reservedBody.get("history")).isEmpty();
    }

    @Test
    void 탈퇴한_손님이면_이름과_번호는_null이고_상태는_WITHDRAWN() {
        long id = data.reservation(date, slot, "PICKED_UP");
        jdbcTemplate.update("UPDATE member SET name = '탈퇴회원', phone = NULL, status = 'WITHDRAWN' WHERE id = ?",
                member);

        JsonNode memberNode = detail(id, Role.ADMIN).getBody().get("member");

        assertThat(memberNode.get("name").isNull()).isTrue();
        assertThat(memberNode.get("phone").isNull()).isTrue();
        assertThat(memberNode.get("status").asText()).isEqualTo("WITHDRAWN");
    }

    @Test
    void 없는_예약은_404_손님은_403_토큰이_없으면_401() {
        long id = data.reservation(date, slot, "RESERVED");

        assertThat(detail(Long.MAX_VALUE, Role.ADMIN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(detail(id, Role.CUSTOMER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.getForEntity("/api/v1/admin/reservations/{id}", JsonNode.class, id).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void history(long reservationId, String from, String to, Long actorId, LocalDateTime at) {
        jdbcTemplate.update("""
                INSERT INTO reservation_history (reservation_id, from_status, to_status, actor_id, changed_at)
                VALUES (?, ?, ?, ?, ?)""", reservationId, from, to, actorId, at);
    }

    private ResponseEntity<JsonNode> detail(long reservationId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(role == Role.ADMIN ? admin : member, role, false));
        return restTemplate.exchange("/api/v1/admin/reservations/{id}", HttpMethod.GET, new HttpEntity<>(headers),
                JsonNode.class, reservationId);
    }

    private static List<String> actorTypes(JsonNode body) {
        List<String> types = new ArrayList<>();
        body.get("history").forEach(row -> types.add(row.get("actorType").asText()));
        return types;
    }
}
