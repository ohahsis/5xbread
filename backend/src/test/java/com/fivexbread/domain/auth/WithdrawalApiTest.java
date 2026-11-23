package com.fivexbread.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.domain.member.model.enums.Role;
import com.fivexbread.domain.member.WithdrawalService;
import com.fivexbread.domain.member.WithdrawnPhoneHasher;
import com.fivexbread.domain.saledate.SaleDateTestData;
import com.fivexbread.domain.saledate.StockKeys;
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
import java.util.concurrent.ThreadLocalRandom;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 탈퇴 (AU-07, 13-5 5단계). 손님은 비밀번호 해시와 함께 테이블에 직접 넣고, 예약은 실제 API 로 만든다(경쟁 테스트).
 * 판매일은 12일 뒤, 지금은 오픈 1시간 뒤로 고정한다.
 */
@Import(MutableClockConfig.class)
class WithdrawalApiTest extends IntegrationTestSupport {

    private static final String ME = "/api/v1/members/me";
    private static final String PHONE_PREFIX = "0107776";
    private static final String PASSWORD = "bread2026!";

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    RefreshTokenStore refreshTokenStore;

    @Autowired
    WithdrawnPhoneHasher hasher;

    @Autowired
    WithdrawalService withdrawalService;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    private SaleDateTestData data;
    private LocalDate date;
    private long slot;
    private LocalDateTime now;
    private String passwordHash;
    private final List<Long> created = new ArrayList<>();
    private int seq;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        cleanUp();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(12);
        LocalDateTime openAt = date.minusDays(3).atTime(10, 0);
        data.saleDate(date, openAt, date.minusDays(1).atTime(20, 0), false);
        slot = data.slot(date, 11);
        data.stock(date, 3, 500, 500, 0, null);
        now = openAt.plusHours(1);
        mutableClock.set(now);
        passwordHash = passwordEncoder.encode(PASSWORD);
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        cleanUp();
    }

    @Test
    void 탈퇴하면_204이고_개인정보는_지우고_예약과_이력은_남긴다() {
        long member = member();
        String phone = phone(member);
        long pickedUp = reservation(member, "PICKED_UP");
        long canceled = reservation(member, "CANCELED");

        ResponseEntity<Void> response = withdraw(member, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).startsWith("refresh_token=;")
                .contains("Max-Age=0").contains("Path=/api/v1/auth");
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM member WHERE id = ?", member);
        assertThat(row.get("name")).isEqualTo("탈퇴회원");
        assertThat(row.get("phone")).isNull();
        assertThat(row.get("status")).isEqualTo("WITHDRAWN");
        assertThat(row.get("password_hash")).isNotEqualTo(passwordHash);
        assertThat(row.get("reservation_blocked_until")).isNull();
        assertThat(row.get("withdrawn_phone_hash")).isEqualTo(hasher.hash(phone)).asString().hasSize(64)
                .doesNotContain(phone);
        assertThat(((LocalDateTime) row.get("withdrawn_at"))).isEqualTo(now);
        assertThat(jdbcTemplate.queryForList("SELECT phone_last4 FROM reservation WHERE member_id = ?", String.class,
                member)).containsExactly("****", "****");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation_history WHERE reservation_id IN (?, ?)",
                Integer.class, pickedUp, canceled)).isEqualTo(2);
    }

    @Test
    void 탈퇴하면_Refresh_Token은_전부_폐기되고_로그인도_남은_토큰도_401() {
        long member = member();
        String phone = phone(member);
        String refreshToken = refreshTokenStore.issue(member);
        refreshTokenStore.issue(member);                                         // 다른 기기

        assertThat(withdraw(member, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(redis.keys("refresh:" + member + ":*")).isEmpty();
        assertThat(redis.hasKey("refresh:index:" + member)).isFalse();
        HttpHeaders cookie = new HttpHeaders();
        cookie.add(HttpHeaders.COOKIE, RefreshTokenCookie.NAME + "=" + refreshToken);
        assertThat(restTemplate.exchange("/api/v1/auth/tokens/current", HttpMethod.PUT, new HttpEntity<>(cookie),
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<JsonNode> login = restTemplate.postForEntity("/api/v1/auth/tokens",
                Map.of("phone", phone, "password", PASSWORD), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 남은 Access Token(최대 30분): 정보 조회·다시 탈퇴·예약은 회원 상태를 보고 401
        assertThat(exchange(HttpMethod.GET, member, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(withdraw(member, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reserve(member).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 진행_중인_예약이_있으면_409이고_아무것도_바뀌지_않는다() {
        long member = member();
        long reserved = reservation(member, "RESERVED");

        ResponseEntity<JsonNode> response = withdrawJson(member, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("ACTIVE_RESERVATION_EXISTS");
        assertThat(status(member)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT phone_last4 FROM reservation WHERE id = ?", String.class, reserved))
                .isNotEqualTo("****");
    }

    @Test
    void 비밀번호가_틀리면_422이고_로그인과_같은_횟수로_잠긴다() {
        long member = member();

        for (int i = 0; i < 4; i++) {
            ResponseEntity<JsonNode> wrong = withdrawJson(member, "wrong2026!");
            assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(wrong.getBody().get("code").asText()).isEqualTo("INVALID_CURRENT_PASSWORD");
        }
        assertThat(withdrawJson(member, "wrong2026!").getStatusCode()).isEqualTo(HttpStatus.LOCKED);   // 5번째
        assertThat(withdrawJson(member, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.LOCKED);       // 맞아도 잠금 중
        assertThat(status(member)).isEqualTo("ACTIVE");
    }

    @Test
    void 사장님은_403이고_본문이_없거나_비밀번호가_비면_400() {
        long admin = member();
        jdbcTemplate.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", admin);
        HttpHeaders headers = bearer(admin, Role.ADMIN);

        ResponseEntity<JsonNode> asAdmin = restTemplate.exchange(ME, HttpMethod.DELETE,
                new HttpEntity<>(Map.of("password", PASSWORD), headers), JsonNode.class);
        long member = member();
        ResponseEntity<JsonNode> noBody = restTemplate.exchange(ME, HttpMethod.DELETE,
                new HttpEntity<>(bearer(member, Role.CUSTOMER)), JsonNode.class);
        ResponseEntity<JsonNode> blank = withdrawJson(member, " ");

        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(admin)).isEqualTo("ACTIVE");
        assertThat(noBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.exchange(ME, HttpMethod.DELETE, new HttpEntity<>(Map.of("password", PASSWORD)),
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 같은_번호는_30일_동안_재가입_422이고_정확히_30일_뒤부터_가입된다() {
        long member = member();
        String phone = phone(member);
        assertThat(withdraw(member, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> signup = Map.of("phone", phone, "password", PASSWORD, "name", "김민건",
                "agreements", Map.of("terms", true, "privacy", true));

        mutableClock.set(now.plusDays(30).minusSeconds(1));
        ResponseEntity<JsonNode> tooEarly = restTemplate.postForEntity("/api/v1/members", signup, JsonNode.class);
        mutableClock.set(now.plusDays(30));
        ResponseEntity<JsonNode> onTime = restTemplate.postForEntity("/api/v1/members", signup, JsonNode.class);

        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooEarly.getBody().get("code").asText()).isEqualTo("REJOIN_RESTRICTED");
        assertThat(tooEarly.getBody().get("rejoinAvailableAt").asText())
                .isEqualTo(now.plusDays(30).toLocalDate() + "T" + now.toLocalTime() + ":00+09:00");
        assertThat(onTime.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        created.add(onTime.getBody().get("memberId").asLong());
    }

    @Test
    void 재가입_제한이_지난_번호_해시만_정리한다() {
        long old = member();
        long recent = member();
        assertThat(withdraw(old, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        mutableClock.set(now.plusDays(10));
        assertThat(withdraw(recent, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        int cleared = withdrawalService.clearExpiredPhoneHashes(now.plusDays(1));

        assertThat(cleared).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT withdrawn_phone_hash FROM member WHERE id = ?", String.class, old))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT withdrawn_phone_hash FROM member WHERE id = ?", String.class,
                recent)).isNotNull();
        assertThat(status(old)).isEqualTo("WITHDRAWN");                         // 회원 행은 남는다
    }

    @Test
    void 같은_회원의_탈퇴를_동시에_두_번_보내면_한_번만_204() throws Exception {
        long member = member();
        Callable<HttpStatus> call = () -> HttpStatus.valueOf(withdraw(member, PASSWORD).getStatusCode().value());

        List<HttpStatus> results = Concurrently.run(List.of(call, call), 2);

        assertThat(results).containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.UNAUTHORIZED);
    }

    /**
     * 탈퇴와 예약을 같은 순간에 보내기를 여러 번 반복한다. 결과는 둘 중 하나여야 한다:
     * 예약이 먼저면 예약 201 + 탈퇴 409, 탈퇴가 먼저면 탈퇴 204 + 예약 실패(401).
     * 어느 쪽이든 "탈퇴했는데 RESERVED 예약이 남는" 회원은 없고, Redis 잔여는 DB 잔여와 같다(실패한 예약의 차감은 보상).
     * 예약에 0~120ms 지연을 무작위로 섞어 두 순서가 모두 나오게 한다. BCrypt 비교가 탈퇴 쪽을 수십 ms 늦춘다.
     */
    @Test
    void 탈퇴와_예약이_동시에_와도_탈퇴한_회원에게_RESERVED가_남지_않고_재고가_맞다() throws Exception {
        int rounds = 30;
        int reservedFirst = 0;
        int withdrawnFirst = 0;
        for (int round = 0; round < rounds; round++) {
            long member = member();
            int delay = ThreadLocalRandom.current().nextInt(0, 120);
            List<Callable<HttpStatus>> calls = List.of(
                    () -> {
                        Thread.sleep(delay);
                        return HttpStatus.valueOf(reserve(member).getStatusCode().value());
                    },
                    () -> HttpStatus.valueOf(withdraw(member, PASSWORD).getStatusCode().value()));

            List<HttpStatus> results = Concurrently.run(calls, 2);
            HttpStatus reserve = results.get(0);
            HttpStatus withdraw = results.get(1);

            if (reserve == HttpStatus.CREATED) {
                assertThat(withdraw).as("round %d", round).isEqualTo(HttpStatus.CONFLICT);
                assertThat(status(member)).isEqualTo("ACTIVE");
                reservedFirst++;
            } else {
                assertThat(withdraw).as("round %d reserve=%s", round, reserve).isEqualTo(HttpStatus.NO_CONTENT);
                assertThat(reserve).as("round %d", round).isEqualTo(HttpStatus.UNAUTHORIZED);
                withdrawnFirst++;
            }
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM reservation r JOIN member m ON m.id = r.member_id
                    WHERE m.status = 'WITHDRAWN' AND r.status = 'RESERVED'""", Integer.class)).isZero();
        }

        int dbReserved = jdbcTemplate.queryForObject(
                "SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = 3", Integer.class, date);
        assertThat(dbReserved).isEqualTo(reservedFirst);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation WHERE sale_date = ?", Integer.class,
                date)).isEqualTo(reservedFirst);
        String redisStock = redis.opsForValue().get(StockKeys.stock(date, 3));   // 한 번도 Redis 까지 못 갔으면 적재 전
        assertThat(redisStock == null ? 500 : Integer.parseInt(redisStock)).isEqualTo(500 - dbReserved);
        System.out.printf("탈퇴·예약 경쟁 %d회: 예약 먼저 %d, 탈퇴 먼저 %d%n", rounds, reservedFirst, withdrawnFirst);
        assertThat(reservedFirst + withdrawnFirst).isEqualTo(rounds);
    }

    private ResponseEntity<JsonNode> reserve(long member) {
        HttpHeaders headers = bearer(member, Role.CUSTOMER);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("saleDate", date.toString(), "pickupSlotId", slot,
                "items", List.of(Map.of("breadId", 3, "quantity", 1)));
        return restTemplate.exchange("/api/v1/reservations", HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private ResponseEntity<Void> withdraw(long member, String password) {
        return restTemplate.exchange(ME, HttpMethod.DELETE,
                new HttpEntity<>(Map.of("password", password), bearer(member, Role.CUSTOMER)), Void.class);
    }

    private ResponseEntity<JsonNode> withdrawJson(long member, String password) {
        return restTemplate.exchange(ME, HttpMethod.DELETE,
                new HttpEntity<>(Map.of("password", password), bearer(member, Role.CUSTOMER)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, long member, Object body) {
        return restTemplate.exchange(ME, method, new HttpEntity<>(body, bearer(member, Role.CUSTOMER)), JsonNode.class);
    }

    private HttpHeaders bearer(long member, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(member, role, false));
        return headers;
    }

    private long member() {
        String phone = PHONE_PREFIX + "%04d".formatted(seq++);
        redis.delete(List.of("login:fail:" + phone, "login:lock:" + phone));
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name, reservation_blocked_until) VALUES (?, ?, ?, ?)",
                phone, passwordHash, "김민건", date.minusDays(20));
        long id = jdbcTemplate.queryForObject("SELECT id FROM member WHERE phone = ?", Long.class, phone);
        created.add(id);
        return id;
    }

    // 회원 id 로 넣은 예약. 번호 뒷 4자리는 회원 번호에서
    private long reservation(long member, String status) {
        String key = UUID.randomUUID().toString();
        String phone = phone(member);
        int no = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation WHERE sale_date = ?", Integer.class, date) + 1;
        jdbcTemplate.update("""
                INSERT INTO reservation (reservation_no, member_id, phone_last4, sale_date, pickup_slot_id, pickup_at,
                                         status, total_price, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, 3500, ?)""", "%02d%02d-%04d".formatted(date.getMonthValue(),
                date.getDayOfMonth(), no), member, phone.substring(7), date, slot, date.atTime(11, 0), status, key);
        long id = jdbcTemplate.queryForObject("SELECT id FROM reservation WHERE idempotency_key = ?", Long.class, key);
        jdbcTemplate.update("""
                INSERT INTO reservation_history (reservation_id, from_status, to_status, actor_id, changed_at)
                VALUES (?, NULL, 'RESERVED', ?, ?)""", id, member, now);
        return id;
    }

    private String phone(long member) {
        return jdbcTemplate.queryForObject("SELECT phone FROM member WHERE id = ?", String.class, member);
    }

    private String status(long member) {
        return jdbcTemplate.queryForObject("SELECT status FROM member WHERE id = ?", String.class, member);
    }

    private void cleanUp() {
        data.clean();
        jdbcTemplate.update("DELETE FROM member_agreement WHERE member_id IN (SELECT id FROM member WHERE phone LIKE ?)",
                PHONE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM member WHERE phone LIKE ?", PHONE_PREFIX + "%");
        for (long id : created) {
            jdbcTemplate.update("DELETE FROM member_agreement WHERE member_id = ?", id);
            jdbcTemplate.update("DELETE FROM member WHERE id = ?", id);
        }
        created.clear();
    }
}
