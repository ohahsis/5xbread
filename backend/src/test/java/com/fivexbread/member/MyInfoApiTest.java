package com.fivexbread.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** 내 정보 조회·이름 수정 (C-10, U-10) */
class MyInfoApiTest extends IntegrationTestSupport {

    private static final String PHONE = "01077771234";
    private static final String ME = "/api/v1/members/me";
    private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtProvider jwtProvider;

    private long member;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM member WHERE phone = ?", PHONE);
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name, reservation_blocked_until) VALUES (?, ?, ?, ?)",
                PHONE, "$2a$10$" + "x".repeat(53), "김민건", LocalDate.of(2026, 12, 1));
        member = jdbcTemplate.queryForObject("SELECT id FROM member WHERE phone = ?", Long.class, PHONE);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM member WHERE id = ?", member);
    }

    @Test
    void 내_정보는_이름_원문과_마스킹한_번호와_예약_제한() {
        ResponseEntity<JsonNode> response = exchange(HttpMethod.GET, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("memberId").asLong()).isEqualTo(member);
        assertThat(body.get("name").asText()).isEqualTo("김민건");
        assertThat(body.get("phone").asText()).isEqualTo("010-****-1234");
        assertThat(body.get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(body.get("reservationBlockedUntil").asText()).isEqualTo("2026-12-01");
        assertThat(body.toString()).doesNotContain(PHONE);
    }

    @Test
    void 이름은_앞뒤_공백을_지우고_저장하고_바뀐_정보를_돌려준다() {
        ResponseEntity<JsonNode> response = exchange(HttpMethod.PATCH, "{\"name\": \"  Kim Min  \"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name").asText()).isEqualTo("Kim Min");
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM member WHERE id = ?", String.class, member))
                .isEqualTo("Kim Min");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\": \"김\"}", "{\"name\": \"김  동건\"}", "{\"name\": \"김동건1\"}",
            "{\"name\": \"가나다라마바사아자차카타파하가나다라마바사\"}", "{\"name\": \"   \"}", "{\"name\": null}", "{}",
            "{\"name\": 3}"})
    void 가입과_같은_이름_규칙에_어긋나면_400이고_저장하지_않는다(String body) {
        ResponseEntity<JsonNode> response = exchange(HttpMethod.PATCH, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(fields(response)).containsExactly("name");
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM member WHERE id = ?", String.class, member))
                .isEqualTo("김민건");
    }

    @Test
    void 이름_말고_다른_필드는_400() {
        ResponseEntity<JsonNode> response = exchange(HttpMethod.PATCH, "{\"name\": \"김동건\", \"phone\": \"01011112222\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fields(response)).containsExactly("phone");
        assertThat(response.getBody().get("errors").get(0).get("code").asText()).isEqualTo("Unknown");
    }

    @Test
    void merge_patch_형식이_아니면_415() {
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> response = restTemplate.exchange(ME, HttpMethod.PATCH,
                new HttpEntity<>("{\"name\": \"김동건\"}", headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void 탈퇴한_회원의_남은_토큰이면_조회도_수정도_401이고_탈퇴회원_이름을_덮지_않는다() {
        jdbcTemplate.update("UPDATE member SET name = '탈퇴회원', phone = NULL, status = 'WITHDRAWN' WHERE id = ?", member);

        assertThat(exchange(HttpMethod.GET, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(HttpMethod.PATCH, "{\"name\": \"김동건\"}").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM member WHERE id = ?", String.class, member))
                .isEqualTo("탈퇴회원");
    }

    @Test
    void 토큰이_없으면_401() {
        assertThat(restTemplate.getForEntity(ME, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String body) {
        HttpHeaders headers = headers();
        if (body != null) {
            headers.setContentType(MERGE_PATCH);
        }
        return restTemplate.exchange(ME, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(member, Role.CUSTOMER, false));
        return headers;
    }

    private static List<String> fields(ResponseEntity<JsonNode> response) {
        List<String> fields = new ArrayList<>();
        response.getBody().get("errors").forEach(error -> fields.add(error.get("field").asText()));
        return fields;
    }
}
