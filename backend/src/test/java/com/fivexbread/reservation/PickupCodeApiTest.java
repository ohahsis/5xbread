package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * 픽업 QR 발급·검증 (C-07, 13-9 1·2단계, 부록 B: 판매일 다음 날 00:00 만료). 판매일 D = 12일 뒤, 예약은 D 전날 12:00 에 만든다.
 */
@Import(MutableClockConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class PickupCodeApiTest extends IntegrationTestSupport {

    private static final long ADMIN_ID = 9_000_001L;
    private static final String VERIFY = "/api/v1/admin/pickup-code-verifications";

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

    @Value("${fivexbread.pickup.secret}")
    String pickupSecret;

    private SaleDateTestData data;
    private LocalDate date;
    private long slot;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(12);
        data.saleDate(date);
        data.stock(date, 3, 100, 50, 0, null);
        slot = data.slot(date, 11);
        now = date.minusDays(1).atTime(12, 0);
        mutableClock.set(now);
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 본인_예약의_QR을_받아_검증하면_201과_예약_정보이고_수령은_하지_않는다() {
        long member = data.memberId();
        long id = reserve(member);

        ResponseEntity<JsonNode> issued = pickupCode(member, id);
        String code = issued.getBody().get("code").asText();
        ResponseEntity<JsonNode> verified = verify(code);

        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        String reservationNo = jdbcTemplate.queryForObject("SELECT reservation_no FROM reservation WHERE id = ?",
                String.class, id);
        long expiresAt = date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toEpochSecond();
        assertThat(code).matches(reservationNo.replace("-", "\\-") + "\\." + expiresAt + "\\.[A-Za-z0-9_-]{43}");
        assertThat(issued.getBody().get("expiresAt").asText()).isEqualTo(date.plusDays(1) + "T00:00:00+09:00");
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(verified.getBody().get("reservationId").asLong()).isEqualTo(id);
        assertThat(verified.getBody().get("reservationNo").asText()).isEqualTo(reservationNo);
        assertThat(verified.getBody().get("status").asText()).isEqualTo("RESERVED");
        assertThat(verified.getBody().get("memberName").asText()).isEqualTo("예**님");     // 예약손님
        assertThat(verified.getBody().get("items").get(0).get("breadId").asLong()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id))
                .isEqualTo("RESERVED");
    }

    @Test
    void 서명_예약번호_만료시각을_한_글자라도_바꾸거나_형식이_틀리면_모두_422() {
        long member = data.memberId();
        String code = pickupCode(member, reserve(member)).getBody().get("code").asText();
        String[] parts = code.split("\\.");
        String signature = parts[2];
        char last = signature.charAt(signature.length() - 1);
        String otherDayExpiry = String.valueOf(Long.parseLong(parts[1]) + 365L * 24 * 3600);   // 1년 뒤 같은 MMDD 의 다음 날

        for (String forged : List.of(
                parts[0] + "." + parts[1] + "." + signature.substring(0, signature.length() - 1) + (last == 'A' ? 'B' : 'A'),
                parts[0].substring(0, 8) + (parts[0].endsWith("9") ? "8" : "9") + "." + parts[1] + "." + signature,
                parts[0] + "." + otherDayExpiry + "." + signature,
                parts[0] + "." + parts[1],
                "not-a-pickup-code",
                code + ".")) {
            ResponseEntity<JsonNode> response = verify(forged);
            assertThat(response.getStatusCode()).as(forged).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_PICKUP_CODE");
            assertThat(response.getBody().has("detail")).isFalse();                // 어느 것이 틀렸는지 알려주지 않음
        }
        assertThat(verify("").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 판매일_다음_날_00시_직전까지_유효하고_00시_정각부터_422() {
        long member = data.memberId();
        String code = pickupCode(member, reserve(member)).getBody().get("code").asText();

        mutableClock.set(date.plusDays(1).atStartOfDay().minusSeconds(1));
        ResponseEntity<JsonNode> before = verify(code);
        mutableClock.set(date.plusDays(1).atStartOfDay());
        ResponseEntity<JsonNode> exactly = verify(code);

        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(exactly.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(exactly.getBody().get("code").asText()).isEqualTo("INVALID_PICKUP_CODE");
    }

    @Test
    void 이미_수령한_예약의_QR도_검증은_되고_상태를_보여준다() {
        long member = data.memberId();
        long id = reserve(member);
        String code = pickupCode(member, id).getBody().get("code").asText();
        mutableClock.set(date.atTime(9, 0));
        HttpHeaders headers = headers(ADMIN_ID, Role.ADMIN);
        headers.setContentType(MediaType.parseMediaType("application/merge-patch+json"));
        assertThat(restTemplate.exchange("/api/v1/admin/reservations/" + id, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "PICKED_UP"), headers), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> verified = verify(code);

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(verified.getBody().get("status").asText()).isEqualTo("PICKED_UP");
    }

    @Test
    void 남의_예약_QR은_404_로그인하지_않으면_401_손님이_검증하면_403() {
        long owner = data.memberId();
        long id = reserve(owner);
        long other = data.members(1).get(0);
        String code = pickupCode(owner, id).getBody().get("code").asText();

        assertThat(pickupCode(other, id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pickupCode(owner, 999_999_999L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/api/v1/reservations/" + id + "/pickup-code", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders customer = headers(owner, Role.CUSTOMER);
        customer.setContentType(MediaType.APPLICATION_JSON);
        assertThat(restTemplate.exchange(VERIFY, HttpMethod.POST, new HttpEntity<>(Map.of("code", code), customer),
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 로그에_QR_토큰과_PICKUP_SECRET_원문이_남지_않는다(CapturedOutput output) {
        long member = data.memberId();
        String code = pickupCode(member, reserve(member)).getBody().get("code").asText();
        verify(code);
        verify(code.substring(0, code.length() - 1) + "x");                         // 실패도
        verify(code + "." + code);

        String signature = code.substring(code.lastIndexOf('.') + 1);
        assertThat(output.getAll()).doesNotContain(signature, pickupSecret);
    }

    private long reserve(long memberId) {
        now = now.plusSeconds(1);
        mutableClock.set(now);
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

    private ResponseEntity<JsonNode> pickupCode(long memberId, long reservationId) {
        return restTemplate.exchange("/api/v1/reservations/" + reservationId + "/pickup-code", HttpMethod.GET,
                new HttpEntity<>(headers(memberId, Role.CUSTOMER)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> verify(String code) {
        HttpHeaders headers = headers(ADMIN_ID, Role.ADMIN);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(VERIFY, HttpMethod.POST, new HttpEntity<>(Map.of("code", code), headers),
                JsonNode.class);
    }

    private HttpHeaders headers(long memberId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, role, false));
        return headers;
    }
}
