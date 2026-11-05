package com.fivexbread.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.AuthMember;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.Map;
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

/** 사장님 회원 검색과 임시 비밀번호 발급 (A-09, C-09, 13-5 3단계) */
@ExtendWith(OutputCaptureExtension.class)
class TemporaryPasswordApiTest extends IntegrationTestSupport {

    private static final String SEARCH_URL = "/api/v1/admin/members?phone={phone}";
    private static final String LOGIN_URL = "/api/v1/auth/tokens";
    private static final String CURRENT_URL = "/api/v1/auth/tokens/current";
    private static final String PHONE = "01012345678";
    private static final String ADMIN_PHONE = "01099998888";
    private static final String PASSWORD = "bread2026!";
    private static final String NEW_PASSWORD = "Fresh-loaf-77";

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

    private long memberId;
    private long adminId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM admin_action_log");
        jdbcTemplate.update("DELETE FROM member_agreement");
        jdbcTemplate.update("DELETE FROM member");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        memberId = insertMember(PHONE, "CUSTOMER");
        adminId = insertMember(ADMIN_PHONE, "ADMIN");
        adminToken = jwtProvider.issue(adminId, Role.ADMIN, false);
    }

    @Test
    void 번호로_찾으면_이름과_마스킹한_번호만_돌려준다() {
        ResponseEntity<JsonNode> response = search(adminToken, "010-1234-5678");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode found = response.getBody().get(0);
        assertThat(response.getBody()).hasSize(1);
        assertThat(found.get("memberId").asLong()).isEqualTo(memberId);
        assertThat(found.get("name").asText()).isEqualTo("김동건");
        assertThat(found.get("phone").asText()).isEqualTo("010-****-5678");
        assertThat(response.getBody().toString()).doesNotContain(PHONE);
    }

    @Test
    void 없는_번호와_사장님_번호는_빈_목록() {
        assertThat(search(adminToken, "01000000000").getBody()).isEmpty();
        assertThat(search(adminToken, ADMIN_PHONE).getBody()).isEmpty();
    }

    @Test
    void 번호_형식이_틀리거나_없으면_400() {
        assertCode(search(adminToken, "1234"), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        ResponseEntity<JsonNode> missing = restTemplate.exchange("/api/v1/admin/members", HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken)), JsonNode.class);
        assertCode(missing, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @Test
    void 손님이_admin_경로를_호출하면_403이고_발급되지_않는다() {
        String customerToken = jwtProvider.issue(memberId, Role.CUSTOMER, false);

        assertCode(search(customerToken, PHONE), HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertCode(issue(customerToken, memberId), HttpStatus.FORBIDDEN, "FORBIDDEN");

        assertThat(logCount()).isZero();
        assertThat(tryLogin(PHONE, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 토큰이_없으면_401() {
        assertCode(issue(null, memberId), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void 비밀번호_변경이_필요한_사장님은_발급할_수_없다() {
        assertCode(issue(jwtProvider.issue(adminId, Role.ADMIN, true), memberId),
                HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED");
    }

    @Test
    void 발급하면_201과_12자_평문이고_DB에는_해시만_남으며_변경_강제가_켜진다() {
        ResponseEntity<JsonNode> response = issue(adminToken, memberId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String temporary = response.getBody().get("temporaryPassword").asText();
        assertThat(temporary).matches("^[A-Za-z0-9]{12}$");
        assertThat(passwordEncoder.matches(temporary, storedHash())).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT must_change_password FROM member WHERE id = ?",
                Boolean.class, memberId)).isTrue();
    }

    @Test
    void 발급하면_기존_리프레시_토큰이_전부_401() {
        String first = cookieValue(tryLogin(PHONE, PASSWORD));
        String second = cookieValue(tryLogin(PHONE, PASSWORD));

        issue(adminToken, memberId);

        assertThat(reissue(first).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reissue(second).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 임시_비밀번호로_로그인하면_mustChangePassword_true이고_이전_비밀번호는_401() {
        String temporary = issue(adminToken, memberId).getBody().get("temporaryPassword").asText();

        ResponseEntity<JsonNode> login = tryLogin(PHONE, temporary);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(login.getBody().get("mustChangePassword").asBoolean()).isTrue();
        assertThat(jwtProvider.parse(login.getBody().get("accessToken").asText()))
                .isEqualTo(new AuthMember(memberId, Role.CUSTOMER, true));
        assertThat(tryLogin(PHONE, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 임시_비밀번호로_재발급해도_변경_강제가_유지되고_변경하면_풀린다() {
        String temporary = issue(adminToken, memberId).getBody().get("temporaryPassword").asText();
        ResponseEntity<JsonNode> login = tryLogin(PHONE, temporary);

        ResponseEntity<JsonNode> reissued = reissue(cookieValue(login));

        assertThat(reissued.getBody().get("mustChangePassword").asBoolean()).isTrue();

        HttpHeaders headers = bearer(reissued.getBody().get("accessToken").asText());
        ResponseEntity<JsonNode> changed = restTemplate.exchange("/api/v1/members/me/password", HttpMethod.PUT,
                new HttpEntity<>(Map.of("currentPassword", temporary, "newPassword", NEW_PASSWORD), headers),
                JsonNode.class);

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tryLogin(PHONE, NEW_PASSWORD).getBody().get("mustChangePassword").asBoolean()).isFalse();
    }

    @Test
    void 비밀번호를_틀려_잠긴_손님도_임시_비밀번호로_바로_로그인한다() {
        for (int i = 0; i < 5; i++) {
            tryLogin(PHONE, "wrong-pass-1");
        }
        assertCode(tryLogin(PHONE, PASSWORD), HttpStatus.LOCKED, "ACCOUNT_LOCKED");

        String temporary = issue(adminToken, memberId).getBody().get("temporaryPassword").asText();

        assertThat(tryLogin(PHONE, temporary).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 발급_이력에_누가_누구에게_언제를_남기고_평문은_남기지_않는다() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(5);

        String temporary = issue(adminToken, memberId).getBody().get("temporaryPassword").asText();

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM admin_action_log");
        assertThat(row.get("admin_id")).isEqualTo(adminId);
        assertThat(row.get("action")).isEqualTo("TEMPORARY_PASSWORD_ISSUED");
        assertThat(row.get("target_type")).isEqualTo("MEMBER");
        assertThat(row.get("target_id")).isEqualTo(String.valueOf(memberId));
        assertThat((LocalDateTime) row.get("created_at")).isAfter(before);
        assertThat(row.get("detail")).isNull();
        assertThat(row.toString()).doesNotContain(temporary);
    }

    @Test
    void 없는_회원_사장님_탈퇴_회원은_404이고_이력이_없다() {
        long withdrawn = insertMember("01055556666", "CUSTOMER");
        jdbcTemplate.update("UPDATE member SET status = 'WITHDRAWN', phone = NULL WHERE id = ?", withdrawn);
        String adminHash = jdbcTemplate.queryForObject("SELECT password_hash FROM member WHERE id = ?", String.class, adminId);

        assertCode(issue(adminToken, 999_999_999L), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertCode(issue(adminToken, adminId), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertCode(issue(adminToken, withdrawn), HttpStatus.NOT_FOUND, "NOT_FOUND");

        assertThat(logCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM member WHERE id = ?", String.class, adminId))
                .isEqualTo(adminHash);
    }

    @Test
    void 로그에_임시_비밀번호와_변경한_비밀번호_원문과_번호_원문이_없다(CapturedOutput output) {
        search(adminToken, PHONE);
        String temporary = issue(adminToken, memberId).getBody().get("temporaryPassword").asText();
        ResponseEntity<JsonNode> login = tryLogin(PHONE, temporary);
        HttpHeaders headers = bearer(login.getBody().get("accessToken").asText());
        restTemplate.exchange("/api/v1/members/me/password", HttpMethod.PUT,
                new HttpEntity<>(Map.of("currentPassword", temporary, "newPassword", NEW_PASSWORD), headers),
                JsonNode.class);

        assertThat(output.getAll()).doesNotContain(temporary, NEW_PASSWORD, PHONE);
    }

    @Test
    void 응답_DTO의_toString에_평문이_없다() {
        assertThat(new TemporaryPasswordResponse("Hq7mT2xPa9Lc").toString()).doesNotContain("Hq7mT2xPa9Lc");
    }

    private ResponseEntity<JsonNode> search(String accessToken, String phone) {
        return restTemplate.exchange(SEARCH_URL, HttpMethod.GET, new HttpEntity<>(bearer(accessToken)),
                JsonNode.class, phone);
    }

    private ResponseEntity<JsonNode> issue(String accessToken, long targetId) {
        return restTemplate.exchange("/api/v1/admin/members/" + targetId + "/temporary-passwords", HttpMethod.POST,
                new HttpEntity<>(bearer(accessToken)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> tryLogin(String phone, String password) {
        return restTemplate.postForEntity(LOGIN_URL, Map.of("phone", phone, "password", password), JsonNode.class);
    }

    private ResponseEntity<JsonNode> reissue(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshToken);
        return restTemplate.exchange(CURRENT_URL, HttpMethod.PUT, new HttpEntity<>(headers), JsonNode.class);
    }

    private long insertMember(String phone, String role) {
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name, role) VALUES (?, ?, ?, ?)",
                phone, passwordEncoder.encode(PASSWORD), "김동건", role);
        return jdbcTemplate.queryForObject("SELECT id FROM member WHERE phone = ?", Long.class, phone);
    }

    private String storedHash() {
        return jdbcTemplate.queryForObject("SELECT password_hash FROM member WHERE id = ?", String.class, memberId);
    }

    private int logCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_action_log", Integer.class);
    }

    private static HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return headers;
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
