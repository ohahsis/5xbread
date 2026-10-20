package com.fivexbread.auth;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.member.Role;
import com.fivexbread.support.IntegrationTestSupport;
import java.time.Duration;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class LoginApiTest extends IntegrationTestSupport {

    private static final String URL = "/api/v1/auth/tokens";
    private static final String PHONE = "01012345678";
    private static final String PASSWORD = "bread2026!";
    private static final String FAIL_KEY = "login:fail:" + PHONE;
    private static final String LOCK_KEY = "login:lock:" + PHONE;
    private static final String WRONG = "wrong-pass-1";

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    PasswordEncoder passwordEncoder;                                  // 비밀번호 비교가 몇 번 일어났는지 확인

    @Autowired
    JwtProvider jwtProvider;

    private long memberId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM member_agreement");
        jdbcTemplate.update("DELETE FROM member");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        memberId = insertMember(PHONE, PASSWORD, false);
    }

    @Test
    void 로그인하면_201과_Access_Token_수명_비밀번호_변경_필요_여부() {
        ResponseEntity<JsonNode> response = login("010-1234-5678", PASSWORD);   // 하이픈을 넣어도 같은 번호

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body.get("expiresIn").asLong()).isEqualTo(1800);
        assertThat(body.get("mustChangePassword").asBoolean()).isFalse();
        assertThat(jwtProvider.parse(body.get("accessToken").asText()))
                .isEqualTo(new AuthMember(memberId, Role.CUSTOMER, false));
    }

    @Test
    void 비밀번호_변경이_필요한_계정은_mustChangePassword_true_토큰에도_pcr() {
        long adminId = insertMember("01099998888", "temp-pass-1234", true);
        jdbcTemplate.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", adminId);

        JsonNode body = login("01099998888", "temp-pass-1234").getBody();

        assertThat(body.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(jwtProvider.parse(body.get("accessToken").asText()))
                .isEqualTo(new AuthMember(adminId, Role.ADMIN, true));
    }

    @Test
    void 틀린_비밀번호와_없는_번호는_같은_401_응답() {
        ResponseEntity<JsonNode> wrongPassword = login(PHONE, WRONG);
        ResponseEntity<JsonNode> unknownPhone = login("01000000000", PASSWORD);

        for (ResponseEntity<JsonNode> response : List.of(wrongPassword, unknownPhone)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(response.getBody().get("title").asText()).isEqualTo("번호 또는 비밀번호가 틀렸습니다");
        }
        // 없는 번호도 똑같이 세야 실패 횟수로 가입 여부를 알 수 없다
        assertThat(redisTemplate.opsForValue().get(FAIL_KEY)).isEqualTo("1");
        assertThat(redisTemplate.opsForValue().get("login:fail:01000000000")).isEqualTo("1");
    }

    @Test
    void 형식이_틀린_번호는_400이고_실패로_세지_않는다() {
        ResponseEntity<JsonNode> response = login("011-1234-5678", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(redisTemplate.keys("login:*")).isEmpty();
    }

    @Test
    void 네_번_실패_후_5번째_실패에서_423_잠금_키_TTL_약_600초() {
        for (int i = 1; i <= 4; i++) {
            assertThat(login(PHONE, WRONG).getStatusCode()).as("%d번째", i).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(redisTemplate.hasKey(LOCK_KEY)).isFalse();

        ResponseEntity<JsonNode> fifth = login(PHONE, WRONG);

        assertThat(fifth.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(fifth.getBody().get("code").asText()).isEqualTo("ACCOUNT_LOCKED");
        assertThat(retryAfter(fifth)).isBetween(590L, 600L);
        assertThat(redisTemplate.getExpire(LOCK_KEY, TimeUnit.SECONDS)).isBetween(590L, 600L);
        assertThat(redisTemplate.opsForValue().get(FAIL_KEY)).isEqualTo("5");   // 잠겨도 횟수는 성공 전까지 남음

        // 다른 번호는 영향 없음
        insertMember("01011112222", PASSWORD, false);
        assertThat(login("01011112222", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 잠긴_상태에서_다시_시도해도_잠금_TTL이_600초로_늘어나지_않고_세지도_않는다() {
        lock();
        redisTemplate.expire(LOCK_KEY, Duration.ofSeconds(100));     // 잠긴 뒤 시간이 흘러 100초 남은 상황

        for (int i = 1; i <= 5; i++) {
            ResponseEntity<JsonNode> response = login(PHONE, WRONG);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
            assertThat(retryAfter(response)).isBetween(1L, 100L);
        }

        assertThat(redisTemplate.getExpire(LOCK_KEY, TimeUnit.SECONDS)).isBetween(1L, 100L);
        assertThat(redisTemplate.opsForValue().get(FAIL_KEY)).isEqualTo("5");
    }

    @Test
    void 잠긴_상태에서는_올바른_비밀번호여도_423이고_실패_횟수도_지우지_않는다() {
        lock();

        ResponseEntity<JsonNode> response = login(PHONE, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(retryAfter(response)).isBetween(590L, 600L);
        assertThat(redisTemplate.opsForValue().get(FAIL_KEY)).isEqualTo("5");
    }

    @Test
    void 잠금이_풀린_뒤_올바른_비밀번호면_로그인되고_실패_횟수를_지운다() {
        lock();

        redisTemplate.delete(LOCK_KEY);                               // 10분 경과 대신 TTL 만료를 흉내

        assertThat(login(PHONE, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(redisTemplate.hasKey(FAIL_KEY)).isFalse();
    }

    @Test
    void 잠금이_풀린_뒤에도_성공_전이면_한_번_더_틀릴_때_바로_다시_10분_잠긴다() {
        lock();
        redisTemplate.delete(LOCK_KEY);

        ResponseEntity<JsonNode> response = login(PHONE, WRONG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(redisTemplate.getExpire(LOCK_KEY, TimeUnit.SECONDS)).isBetween(590L, 600L);
        assertThat(redisTemplate.opsForValue().get(FAIL_KEY)).isEqualTo("6");
    }

    @Test
    void 로그인에_성공하면_실패_카운터를_지운다() {
        for (int i = 1; i <= 4; i++) {
            login(PHONE, WRONG);
        }
        assertThat(redisTemplate.opsForValue().get(FAIL_KEY)).isEqualTo("4");

        assertThat(login(PHONE, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(redisTemplate.hasKey(FAIL_KEY)).isFalse();

        // 연속 실패가 끊겼으므로 다시 4번 틀려도 잠기지 않는다
        for (int i = 1; i <= 4; i++) {
            login(PHONE, WRONG);
        }
        assertThat(login(PHONE, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 실패_카운터는_정리용_TTL_24시간을_실패할_때마다_갱신한다() {
        login(PHONE, WRONG);
        assertThat(redisTemplate.getExpire(FAIL_KEY, TimeUnit.SECONDS)).isBetween(86_390L, 86_400L);

        redisTemplate.expire(FAIL_KEY, Duration.ofSeconds(100));     // 시간이 흘러 100초 남은 상황
        login(PHONE, WRONG);

        assertThat(redisTemplate.getExpire(FAIL_KEY, TimeUnit.SECONDS)).isBetween(86_390L, 86_400L);
        assertThat(redisTemplate.opsForValue().get(FAIL_KEY)).isEqualTo("2");
    }

    @Test
    void 다섯_번째_시도가_맞으면_로그인되고_잠금_키와_횟수를_지운다() {
        for (int i = 1; i <= 4; i++) {
            login(PHONE, WRONG);
        }

        assertThat(login(PHONE, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(redisTemplate.hasKey(LOCK_KEY)).isFalse();
        assertThat(redisTemplate.hasKey(FAIL_KEY)).isFalse();
    }

    @Test
    void 동시에_틀린_비밀번호_20건이면_비교는_5건뿐이고_나머지는_세지_않고_423() throws Exception {
        int requests = 20;
        List<HttpStatus> statuses = concurrently(requests, () -> login(PHONE, WRONG));

        // 비교 전에 세므로 비밀번호 비교까지 간 건 5건뿐. 1~4번째만 401, 5번째(잠금을 만든 시도)와 나머지는 423
        verify(passwordEncoder, times(5)).matches(eq(WRONG), anyString());
        assertThat(statuses).filteredOn(HttpStatus.UNAUTHORIZED::equals).hasSize(4);
        assertThat(statuses).filteredOn(HttpStatus.LOCKED::equals).hasSize(requests - 4);
        assertThat(redisTemplate.getExpire(LOCK_KEY, TimeUnit.SECONDS)).isBetween(590L, 600L);
        assertThat(redisTemplate.opsForValue().get(FAIL_KEY)).isEqualTo("5");   // 잠긴 뒤 시도는 세지 않음
    }

    @Test
    void 잠금이_풀린_뒤_동시에_20건이_와도_비교는_1건이고_틀리면_다시_잠긴다() throws Exception {
        lock();
        redisTemplate.delete(LOCK_KEY);
        clearInvocations(passwordEncoder);

        List<HttpStatus> statuses = concurrently(20, () -> login(PHONE, WRONG));

        verify(passwordEncoder, times(1)).matches(eq(WRONG), anyString());
        assertThat(statuses).containsOnly(HttpStatus.LOCKED);
        assertThat(redisTemplate.getExpire(LOCK_KEY, TimeUnit.SECONDS)).isBetween(590L, 600L);
        assertThat(redisTemplate.opsForValue().get(FAIL_KEY)).isEqualTo("6");
    }

    private static List<HttpStatus> concurrently(int requests, Callable<ResponseEntity<JsonNode>> call) throws Exception {
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

    private void lock() {
        for (int i = 1; i <= 5; i++) {
            login(PHONE, WRONG);
        }
        assertThat(redisTemplate.hasKey(LOCK_KEY)).isTrue();
    }

    private static long retryAfter(ResponseEntity<JsonNode> response) {
        return Long.parseLong(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    private ResponseEntity<JsonNode> login(String phone, String password) {
        return restTemplate.postForEntity(URL, Map.of("phone", phone, "password", password), JsonNode.class);
    }

    private long insertMember(String phone, String password, boolean mustChangePassword) {
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name, must_change_password) VALUES (?, ?, ?, ?)",
                phone, passwordEncoder.encode(password), "김동건", mustChangePassword);
        return jdbcTemplate.queryForObject("SELECT id FROM member WHERE phone = ?", Long.class, phone);
    }
}
