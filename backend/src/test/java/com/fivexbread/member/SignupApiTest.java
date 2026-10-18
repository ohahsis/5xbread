package com.fivexbread.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class SignupApiTest extends IntegrationTestSupport {

    private static final String URL = "/api/v1/members";

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM member_agreement");
        jdbcTemplate.update("DELETE FROM member");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void 가입하면_201_Location_마스킹된_번호를_돌려주고_해시와_약관_동의를_저장한다() {
        ResponseEntity<JsonNode> response = signUp("010-1234-5678", "203.0.113.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long memberId = response.getBody().get("memberId").asLong();
        assertThat(response.getHeaders().getLocation()).hasToString("/api/v1/members/" + memberId);
        assertThat(response.getBody().get("phone").asText()).isEqualTo("010-****-5678");
        assertThat(response.getBody().get("name").asText()).isEqualTo("김동건");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT phone, password_hash, role, status, must_change_password FROM member WHERE id = ?", memberId);
        assertThat(row.get("phone")).isEqualTo("01012345678");
        assertThat(row.get("role")).isEqualTo("CUSTOMER");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("must_change_password")).isEqualTo(false);
        String hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("$2a$10$");                        // BCrypt cost 10
        assertThat(passwordEncoder.matches("bread2026!", hash)).isTrue();

        Map<String, Object> agreement = jdbcTemplate.queryForMap(
                "SELECT terms_version, privacy_version, agreed_at FROM member_agreement WHERE member_id = ?", memberId);
        assertThat(agreement.get("terms_version")).isEqualTo("v1");
        assertThat(agreement.get("privacy_version")).isEqualTo("v1");
        assertThat(agreement.get("agreed_at")).isNotNull();
    }

    @Test
    void 형식이_틀리면_400과_필드별_errors() {
        ResponseEntity<JsonNode> response = post(Map.of(
                "phone", "011-1234-5678", "password", "bread12345678", "name", "김동건",
                "agreements", Map.of("terms", true, "privacy", true)), "203.0.113.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        List<String> fields = new ArrayList<>();
        response.getBody().get("errors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).containsExactly("phone");
    }

    @Test
    void 비밀번호에_번호_뒷_8자리가_있으면_400_password_오류() {
        ResponseEntity<JsonNode> response = post(body("010-1234-5678", "bread12345678"), "203.0.113.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = response.getBody().get("errors").get(0);
        assertThat(error.get("field").asText()).isEqualTo("password");
        assertThat(error.get("message").asText()).isEqualTo(PasswordRules.PHONE_MESSAGE);
    }

    @Test
    void 이미_가입한_번호면_409_DUPLICATE_PHONE() {
        assertThat(signUp("010-1234-5678", "203.0.113.1").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> response = signUp("01012345678", "203.0.113.2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("DUPLICATE_PHONE");
        assertThat(count("01012345678")).isEqualTo(1);
    }

    @Test
    void 같은_번호로_동시에_20건_가입하면_1건만_성공한다() throws Exception {
        int requests = 20;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();

        for (int i = 0; i < requests; i++) {
            String ip = "198.51.100." + (i + 1);                         // IP 제한에 걸리지 않게 요청마다 다른 IP
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return HttpStatus.valueOf(signUp("010-5555-0001", ip).getStatusCode().value());
            }));
        }
        ready.await();
        start.countDown();

        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<HttpStatus> result : results) {
            statuses.add(result.get(60, TimeUnit.SECONDS));
        }
        executor.shutdown();

        assertThat(statuses).filteredOn(HttpStatus.CREATED::equals).hasSize(1);
        assertThat(statuses).filteredOn(HttpStatus.CONFLICT::equals).hasSize(requests - 1);
        assertThat(count("01055550001")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_agreement", Integer.class)).isEqualTo(1);
    }

    @Test
    void 같은_IP에서_11번째_가입은_429와_Retry_After() {
        String ip = "203.0.113.77";
        for (int i = 1; i <= 10; i++) {
            ResponseEntity<JsonNode> response = signUp("010-7000-00%02d".formatted(i), ip);
            assertThat(response.getStatusCode()).as("%d번째", i).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<JsonNode> eleventh = signUp("010-7000-0011", ip);

        assertThat(eleventh.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(eleventh.getBody().get("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        long retryAfter = Long.parseLong(eleventh.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 86_400L);
        assertThat(count("01070000011")).isZero();

        // 키는 그날 자정(KST)에 사라진다
        String key = redisTemplate.keys("signup:ip:" + ip + ":*").iterator().next();
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, 86_400L);

        // 다른 IP는 영향 없음
        assertThat(signUp("010-7000-0012", "203.0.113.78").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<JsonNode> signUp(String phone, String clientIp) {
        return post(body(phone, "bread2026!"), clientIp);
    }

    private ResponseEntity<JsonNode> post(Map<String, Object> body, String clientIp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", clientIp);                      // 로컬 연결은 신뢰된 프록시로 취급됨
        return restTemplate.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static Map<String, Object> body(String phone, String password) {
        return Map.of("phone", phone, "password", password, "name", "김동건",
                "agreements", Map.of("terms", true, "privacy", true));
    }

    private int count(String phone) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member WHERE phone = ?", Integer.class, phone);
    }
}
