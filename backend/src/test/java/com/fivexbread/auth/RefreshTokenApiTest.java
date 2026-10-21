package com.fivexbread.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.member.Role;
import com.fivexbread.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Refresh Token 발급·회전·재사용 감지·로그아웃 (13-6). TestRestTemplate 은 쿠키를 저장하지 않아 Cookie 헤더를 직접 붙인다.
 */
class RefreshTokenApiTest extends IntegrationTestSupport {

    private static final String LOGIN_URL = "/api/v1/auth/tokens";
    private static final String CURRENT_URL = "/api/v1/auth/tokens/current";
    private static final String PHONE = "01012345678";
    private static final String PASSWORD = "bread2026!";
    private static final long FOURTEEN_DAYS = 14 * 24 * 60 * 60;

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

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM member_agreement");
        jdbcTemplate.update("DELETE FROM member");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        memberId = insertMember(PHONE);
    }

    @Test
    void 로그인하면_Refresh_쿠키를_주고_Redis에는_해시만_14일_저장한다() {
        ResponseEntity<JsonNode> response = login();

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("refresh_token=" + memberId + ".")
                .contains("Path=/api/v1/auth", "Max-Age=" + FOURTEEN_DAYS, "Secure", "HttpOnly", "SameSite=Strict");
        assertThat(response.getBody().has("refreshToken")).isFalse();

        String token = cookieValue(response);
        String tokenId = RefreshTokenStore.hash(token.substring(token.indexOf('.') + 1));
        String key = RefreshTokenStore.tokenKey(memberId, tokenId);
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(FOURTEEN_DAYS - 10, FOURTEEN_DAYS);
        assertThat(redisTemplate.opsForSet().members(RefreshTokenStore.indexKey(memberId))).containsExactly(tokenId);
        assertThat(redisTemplate.keys("refresh:*")).noneMatch(k -> k.contains(token.substring(token.indexOf('.') + 1)));
    }

    @Test
    void 재발급하면_새_쌍을_주고_새_토큰으로_다시_재발급할_수_있다() {
        String first = cookieValue(login());

        ResponseEntity<JsonNode> reissued = reissue(first);

        assertThat(reissued.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwtProvider.parse(reissued.getBody().get("accessToken").asText()))
                .isEqualTo(new AuthMember(memberId, Role.CUSTOMER, false));
        String second = cookieValue(reissued);
        assertThat(second).isNotEqualTo(first);
        assertThat(reissue(second).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 이미_교체된_토큰을_다시_쓰면_401이고_그_회원의_토큰을_전부_폐기한다() {
        String stolen = cookieValue(login());
        String otherDevice = cookieValue(login());
        String rotated = cookieValue(reissue(stolen));

        ResponseEntity<JsonNode> reused = reissue(stolen);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reused.getBody().get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(reissue(rotated).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reissue(otherDevice).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(redisTemplate.hasKey(RefreshTokenStore.indexKey(memberId))).isFalse();
    }

    @Test
    void memberId만_바꾼_위조_토큰은_401이고_그_회원의_토큰을_폐기하지_않는다() {
        String victim = cookieValue(login());
        String forged = memberId + "." + "A".repeat(43);

        assertThat(reissue(forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reissue(victim).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 형식이_틀린_쿠키는_401() {
        for (String broken : List.of("garbage", "abc." + "A".repeat(43), memberId + ".short", "." + "A".repeat(43))) {
            assertThat(reissue(broken).getStatusCode()).as(broken).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void 재발급하면_바뀐_회원_정보로_Access_Token을_만든다() {
        String token = cookieValue(login());
        jdbcTemplate.update("UPDATE member SET must_change_password = TRUE WHERE id = ?", memberId);

        ResponseEntity<JsonNode> reissued = reissue(token);

        assertThat(reissued.getBody().get("mustChangePassword").asBoolean()).isTrue();
        assertThat(jwtProvider.parse(reissued.getBody().get("accessToken").asText()).passwordChangeRequired()).isTrue();
    }

    @Test
    void 활성_상태가_아닌_회원은_재발급할_수_없다() {
        String token = cookieValue(login());
        jdbcTemplate.update("UPDATE member SET status = 'LOCKED' WHERE id = ?", memberId);

        assertThat(reissue(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 로그아웃하면_그_토큰만_지워지고_다른_기기_토큰은_남는다() {
        String mine = cookieValue(login());
        String otherDevice = cookieValue(login());

        ResponseEntity<Void> response = logout(mine);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(reissue(mine).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reissue(otherDevice).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 이미_로그아웃했거나_형식이_틀린_토큰으로_로그아웃해도_204() {
        String token = cookieValue(login());
        logout(token);

        assertThat(logout(token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(logout("garbage").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void 같은_토큰으로_동시에_10건_재발급하면_1건만_성공한다() throws Exception {
        String token = cookieValue(login());
        int requests = 10;

        List<HttpStatus> statuses = concurrently(requests, () -> reissue(token));

        assertThat(statuses).filteredOn(HttpStatus.OK::equals).hasSize(1);
        assertThat(statuses).filteredOn(HttpStatus.UNAUTHORIZED::equals).hasSize(requests - 1);
    }

    private static List<HttpStatus> concurrently(int requests, Callable<ResponseEntity<JsonNode>> call)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return HttpStatus.valueOf(call.call().getStatusCode().value());
            }));
        }
        ready.await();
        start.countDown();

        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<HttpStatus> result : results) {
            statuses.add(result.get(60, TimeUnit.SECONDS));
        }
        executor.shutdown();
        return statuses;
    }

    private ResponseEntity<JsonNode> login() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(LOGIN_URL,
                Map.of("phone", PHONE, "password", PASSWORD), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private ResponseEntity<JsonNode> reissue(String refreshToken) {
        return restTemplate.exchange(CURRENT_URL, HttpMethod.PUT, withCookie(refreshToken), JsonNode.class);
    }

    private ResponseEntity<Void> logout(String refreshToken) {
        return restTemplate.exchange(CURRENT_URL, HttpMethod.DELETE, withCookie(refreshToken), Void.class);
    }

    private static HttpEntity<Void> withCookie(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, RefreshTokenCookie.NAME + "=" + refreshToken);
        return new HttpEntity<>(headers);
    }

    // Set-Cookie: refresh_token={값}; Path=...
    private static String cookieValue(ResponseEntity<?> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private long insertMember(String phone) {
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name) VALUES (?, ?, ?)",
                phone, passwordEncoder.encode(PASSWORD), "김동건");
        return jdbcTemplate.queryForObject("SELECT id FROM member WHERE phone = ?", Long.class, phone);
    }
}
