package com.fivexbread.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.member.MemberService;
import com.fivexbread.member.Role;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.support.Concurrently;
import com.fivexbread.support.IntegrationTestSupport;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 비밀번호 변경 (C-10, 13-5 2단계)과 변경 강제 (AU-06, AU-08) */
@ExtendWith(OutputCaptureExtension.class)
class PasswordChangeApiTest extends IntegrationTestSupport {

    private static final String URL = "/api/v1/members/me/password";
    private static final String LOGIN_URL = "/api/v1/auth/tokens";
    private static final String CURRENT_URL = "/api/v1/auth/tokens/current";
    private static final String PHONE = SaleDateTestData.MEMBER_PHONE;      // 01077770000
    private static final String OTHER_PHONE = "01012345678";
    private static final String PASSWORD = "bread2026!";
    private static final String NEW_PASSWORD = "Fresh-loaf-77";
    private static final String WRONG = "wrong-pass-1";

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    MemberService memberService;

    @Autowired
    Clock clock;

    private SaleDateTestData data;
    private long memberId;

    @BeforeEach
    void setUp() {
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        memberId = data.memberId();
        jdbcTemplate.update("UPDATE member SET password_hash = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), memberId);
    }

    @AfterEach
    void tearDown() {
        data.clean();
        jdbcTemplate.update("DELETE FROM member WHERE phone = ?", OTHER_PHONE);
    }

    @Test
    void 변경하면_새_비밀번호로_로그인되고_다른_기기는_401_지금_기기는_새_쿠키로_유지된다() {
        ResponseEntity<JsonNode> here = login(PASSWORD);
        String otherDevice = cookieValue(login(PASSWORD));

        ResponseEntity<JsonNode> changed = change(accessToken(here), PASSWORD, NEW_PASSWORD);

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().get("mustChangePassword").asBoolean()).isFalse();
        assertThat(changed.getBody().has("refreshToken")).isFalse();
        // 요청 경로와 달라도 브라우저가 같은 이름·경로의 기존 쿠키를 덮어쓰도록 로그인 때와 같은 속성으로 내려 준다
        assertThat(changed.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("Path=/api/v1/auth", "HttpOnly", "Secure", "SameSite=Strict");

        assertThat(reissue(otherDevice).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reissue(cookieValue(here)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);   // 덮어써진 이전 값
        assertThat(reissue(cookieValue(changed)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tryLogin(PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(tryLogin(NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 변경하면_must_change_password가_풀리고_응답_Access_Token에_pcr이_없다() {
        jdbcTemplate.update("UPDATE member SET must_change_password = TRUE WHERE id = ?", memberId);
        ResponseEntity<JsonNode> logged = login(PASSWORD);
        assertThat(logged.getBody().get("mustChangePassword").asBoolean()).isTrue();

        ResponseEntity<JsonNode> changed = change(accessToken(logged), PASSWORD, NEW_PASSWORD);

        assertThat(jwtProvider.parse(accessToken(changed)))
                .isEqualTo(new AuthMember(memberId, Role.CUSTOMER, false));
        assertThat(jdbcTemplate.queryForObject("SELECT must_change_password FROM member WHERE id = ?",
                Boolean.class, memberId)).isFalse();
    }

    @Test
    void pcr_상태면_예약_API가_403이고_변경_후_받은_토큰으로_예약된다() {
        LocalDate date = data.today().plusDays(12);
        data.saleDate(date, LocalDateTime.now(clock).minusHours(1), date.minusDays(1).atTime(20, 0), false);
        long slot = data.slot(date, 11);
        data.stock(date, 2, 100, 50, 0, null);
        jdbcTemplate.update("UPDATE member SET must_change_password = TRUE WHERE id = ?", memberId);
        String pcrToken = accessToken(login(PASSWORD));

        ResponseEntity<JsonNode> blocked = reserve(pcrToken, date, slot);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody().get("code").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");

        String newToken = accessToken(change(pcrToken, PASSWORD, NEW_PASSWORD));

        assertThat(reserve(newToken, date, slot).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserve(pcrToken, date, slot).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);   // 예전 토큰은 그대로
    }

    @Test
    void pcr_상태면_내_예약_목록이_403이고_변경_후_받은_토큰으로_200() {
        jdbcTemplate.update("UPDATE member SET must_change_password = TRUE WHERE id = ?", memberId);
        String pcrToken = accessToken(login(PASSWORD));

        assertCode(myReservations(pcrToken), HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED");

        String newToken = accessToken(change(pcrToken, PASSWORD, NEW_PASSWORD));

        assertThat(myReservations(newToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 현재_비밀번호가_틀리면_422이고_바뀌지_않으며_토큰도_그대로다() {
        ResponseEntity<JsonNode> here = login(PASSWORD);

        ResponseEntity<JsonNode> response = change(accessToken(here), WRONG, NEW_PASSWORD);

        assertCode(response, HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CURRENT_PASSWORD");
        assertThat(reissue(cookieValue(here)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tryLogin(PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 새_비밀번호_형식이_틀리면_400이고_시도_횟수를_세지_않는다() {
        String token = accessToken(login(PASSWORD));

        for (String invalid : List.of("short1!", "onlyletters", "1234567890", "breadaaaa2026", "한글비밀번호1a", "a1" + "b".repeat(63))) {
            ResponseEntity<JsonNode> response = change(token, WRONG, invalid);

            assertCode(response, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
            assertThat(response.getBody().get("errors").get(0).get("field").asText()).isEqualTo("newPassword");
        }
        assertThat(redisTemplate.hasKey(LoginFailureCounter.failKey(PHONE))).isFalse();
    }

    @Test
    void 값이_없으면_400() {
        String token = accessToken(login(PASSWORD));

        assertCode(exchange(token, Map.of("newPassword", NEW_PASSWORD)), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertCode(exchange(token, Map.of("currentPassword", PASSWORD)), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @Test
    void 새_비밀번호에_번호_뒷_8자리가_있으면_422() {
        // PHONE 의 뒷 8자리는 0000 이 있어 4연속 금지(400)에 먼저 걸리므로 다른 번호로 확인한다
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name) VALUES (?, ?, ?)",
                OTHER_PHONE, passwordEncoder.encode(PASSWORD), "다른손님");
        ResponseEntity<JsonNode> logged = restTemplate.postForEntity(LOGIN_URL,
                Map.of("phone", OTHER_PHONE, "password", PASSWORD), JsonNode.class);

        assertCode(change(accessToken(logged), PASSWORD, "bread12345678x"),
                HttpStatus.UNPROCESSABLE_ENTITY, "PASSWORD_CONTAINS_PHONE");
    }

    @Test
    void 새_비밀번호가_현재와_같으면_422() {
        ResponseEntity<JsonNode> here = login(PASSWORD);

        assertCode(change(accessToken(here), PASSWORD, PASSWORD),
                HttpStatus.UNPROCESSABLE_ENTITY, "PASSWORD_SAME_AS_CURRENT");
        assertThat(reissue(cookieValue(here)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 현재_비밀번호를_5번_틀리면_423이고_맞는_비밀번호도_로그인도_막힌다() {
        String token = accessToken(login(PASSWORD));

        for (int i = 0; i < 4; i++) {
            assertCode(change(token, WRONG, NEW_PASSWORD), HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CURRENT_PASSWORD");
        }
        ResponseEntity<JsonNode> fifth = change(token, WRONG, NEW_PASSWORD);

        assertCode(fifth, HttpStatus.LOCKED, "ACCOUNT_LOCKED");
        assertThat(fifth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertCode(change(token, PASSWORD, NEW_PASSWORD), HttpStatus.LOCKED, "ACCOUNT_LOCKED");
        assertCode(tryLogin(PASSWORD), HttpStatus.LOCKED, "ACCOUNT_LOCKED");
    }

    @Test
    void 로그인_실패와_변경_실패를_합쳐_5번에_잠긴다() {
        String token = accessToken(login(PASSWORD));
        for (int i = 0; i < 3; i++) {
            assertThat(tryLogin(WRONG).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertCode(change(token, WRONG, NEW_PASSWORD), HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CURRENT_PASSWORD");
        assertCode(change(token, WRONG, NEW_PASSWORD), HttpStatus.LOCKED, "ACCOUNT_LOCKED");
    }

    @Test
    void 현재_비밀번호가_맞으면_시도_횟수가_초기화된다() {
        String token = accessToken(login(PASSWORD));
        change(token, WRONG, NEW_PASSWORD);

        assertCode(change(token, PASSWORD, PASSWORD), HttpStatus.UNPROCESSABLE_ENTITY, "PASSWORD_SAME_AS_CURRENT");

        assertThat(redisTemplate.hasKey(LoginFailureCounter.failKey(PHONE))).isFalse();
    }

    @Test
    void 토큰이_없으면_401() {
        assertCode(change(null, PASSWORD, NEW_PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void 활성_상태가_아닌_회원은_401() {
        String token = accessToken(login(PASSWORD));
        jdbcTemplate.update("UPDATE member SET status = 'LOCKED' WHERE id = ?", memberId);

        assertCode(change(token, PASSWORD, NEW_PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void 같은_현재_비밀번호로_동시에_3건_변경하면_1건만_성공하고_그_비밀번호만_남는다() throws Exception {
        String token = accessToken(login(PASSWORD));
        List<String> newPasswords = List.of("Fresh-loaf-01", "Fresh-loaf-02", "Fresh-loaf-03");
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (String newPassword : newPasswords) {
            tasks.add(() -> change(token, PASSWORD, newPassword));
        }

        List<ResponseEntity<JsonNode>> responses = Concurrently.run(tasks, tasks.size());

        List<String> succeeded = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            if (responses.get(i).getStatusCode() == HttpStatus.OK) {
                succeeded.add(newPasswords.get(i));
            } else {
                assertCode(responses.get(i), HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CURRENT_PASSWORD");
            }
        }
        assertThat(succeeded).hasSize(1);
        assertThat(passwordEncoder.matches(succeeded.get(0), storedHash())).isTrue();
    }

    @Test
    void 확인한_뒤_비밀번호가_바뀌었으면_덮어쓰지_않는다() {
        String afterIssue = passwordEncoder.encode("Temporary-pw-1");
        String checkedHash = storedHash();
        jdbcTemplate.update("UPDATE member SET password_hash = ?, must_change_password = TRUE WHERE id = ?",
                afterIssue, memberId);    // 현재 비밀번호 확인 뒤에 사장님이 임시 비밀번호를 발급한 상황

        boolean saved = memberService.changePassword(memberId, checkedHash, passwordEncoder.encode(NEW_PASSWORD));

        assertThat(saved).isFalse();
        assertThat(storedHash()).isEqualTo(afterIssue);
    }

    @Test
    void 로그에_현재_비밀번호와_새_비밀번호_원문이_없다(CapturedOutput output) {
        String token = accessToken(login(PASSWORD));
        String wrongOnPurpose = "Never-log-this-1";
        String invalidNew = "never-log-invalid";

        change(token, wrongOnPurpose, NEW_PASSWORD);                    // 422
        change(token, PASSWORD, invalidNew);                            // 400
        assertThat(change(token, PASSWORD, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(output.getAll()).doesNotContain(PASSWORD, NEW_PASSWORD, wrongOnPurpose, invalidNew);
    }

    @Test
    void 요청_DTO의_toString에_원문이_없다() {
        assertThat(new PasswordChangeRequest(PASSWORD, NEW_PASSWORD).toString()).doesNotContain(PASSWORD, NEW_PASSWORD);
    }

    private String storedHash() {
        return jdbcTemplate.queryForObject("SELECT password_hash FROM member WHERE id = ?", String.class, memberId);
    }

    private ResponseEntity<JsonNode> myReservations(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return restTemplate.exchange("/api/v1/members/me/reservations", HttpMethod.GET, new HttpEntity<>(headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> reserve(String accessToken, LocalDate date, long slot) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("saleDate", date.toString(), "pickupSlotId", slot,
                "items", List.of(Map.of("breadId", 2, "quantity", 1)));
        return restTemplate.exchange("/api/v1/reservations", HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> change(String accessToken, String currentPassword, String newPassword) {
        return exchange(accessToken, Map.of("currentPassword", currentPassword, "newPassword", newPassword));
    }

    private ResponseEntity<JsonNode> exchange(String accessToken, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return restTemplate.exchange(URL, HttpMethod.PUT, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> login(String password) {
        ResponseEntity<JsonNode> response = tryLogin(password);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private ResponseEntity<JsonNode> tryLogin(String password) {
        return restTemplate.postForEntity(LOGIN_URL, Map.of("phone", PHONE, "password", password), JsonNode.class);
    }

    private ResponseEntity<JsonNode> reissue(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, RefreshTokenCookie.NAME + "=" + refreshToken);
        return restTemplate.exchange(CURRENT_URL, HttpMethod.PUT, new HttpEntity<>(headers), JsonNode.class);
    }

    private static String accessToken(ResponseEntity<JsonNode> response) {
        return response.getBody().get("accessToken").asText();
    }

    private static String cookieValue(ResponseEntity<?> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private static void assertCode(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }
}
