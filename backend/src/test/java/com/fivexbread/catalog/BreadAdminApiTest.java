package com.fivexbread.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/**
 * 빵 등록·수정·판매 중지 (A-01). V2 시드 빵 25종(id 1~25)은 건드리지 않고, 테스트가 만든 빵만 지운다.
 */
class BreadAdminApiTest extends IntegrationTestSupport {

    private static final String URL = "/api/v1/admin/breads";
    private static final long SEED_BREADS = 25;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM daily_stock WHERE bread_id > ?", SEED_BREADS);
        jdbcTemplate.update("DELETE FROM bread WHERE id > ?", SEED_BREADS);
    }

    @Test
    void 손님은_403_로그인하지_않으면_401() {
        assertThat(call(HttpMethod.POST, URL, body("잡곡빵"), Role.CUSTOMER).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, URL, null, Role.CUSTOMER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.postForEntity(URL, body("잡곡빵"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("잡곡빵")).isZero();
    }

    @Test
    void 등록하면_201_Location과_판매_중인_빵() {
        Map<String, Object> request = body(" 잡곡 빵 ");                           // 앞뒤 공백은 지움
        request.put("allergens", List.of("EGG", "WHEAT", "MILK"));
        request.put("description", "호두와 해바라기씨를 넣은 빵");

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, URL, request, Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode bread = response.getBody();
        long id = bread.get("id").asLong();
        assertThat(response.getHeaders().getLocation()).hasToString(URL + "/" + id);
        assertThat(bread.get("name").asText()).isEqualTo("잡곡 빵");
        assertThat(bread.get("active").asBoolean()).isTrue();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT category, price, allergens, display_order, active_name FROM bread WHERE id = ?", id);
        assertThat(row.get("category")).isEqualTo("HARD_BREAD");
        assertThat(row.get("price")).isEqualTo(4500);
        assertThat(row.get("allergens")).isEqualTo("EGG,MILK,WHEAT");             // enum 선언 순서로 고정
        assertThat(row.get("active_name")).isEqualTo("잡곡빵");
    }

    @Test
    void 필드_규격을_어기면_400과_필드별_errors() {
        Map<String, Object> request = body("잡곡빵<script>");
        request.put("price", 4550);
        request.put("description", "1\n2\n3\n4\n5");
        request.put("displayOrder", 0);

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, URL, request, Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        List<String> fields = new ArrayList<>();
        response.getBody().get("errors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).contains("name", "priceHundredUnit", "descriptionWithinLineBreaks", "displayOrder");
    }

    @Test
    void 판매_중인_빵과_이름이_같으면_공백_대소문자를_무시하고_409() {
        assertThat(call(HttpMethod.POST, URL, body("Plain Roll"), Role.ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, URL, body("plainroll"), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("DUPLICATE_BREAD_NAME");
        assertThat(call(HttpMethod.POST, URL, body("소금빵"), Role.ADMIN).getStatusCode())   // 시드 1번
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 판매_중지된_빵과_이름이_같으면_새로_등록할_수_있지만_다시_판매하면_409() {
        long old = id(call(HttpMethod.POST, URL, body("잡곡빵"), Role.ADMIN));
        assertThat(call(HttpMethod.PATCH, URL + "/" + old, Map.of("active", false), Role.ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(call(HttpMethod.POST, URL, body("잡곡빵"), Role.ADMIN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> reactivate = call(HttpMethod.PATCH, URL + "/" + old, Map.of("active", true), Role.ADMIN);

        assertThat(reactivate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM bread WHERE id = ?", Boolean.class, old)).isFalse();
    }

    @Test
    void 판매_중인_빵이_30종이면_더_등록할_수_없다() {
        long active = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bread WHERE active", Long.class);
        for (long i = active; i < Bread.MAX_ACTIVE_BREADS; i++) {
            assertThat(call(HttpMethod.POST, URL, body("추가빵 " + i), Role.ADMIN).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, URL, body("한도초과빵"), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("ACTIVE_BREAD_LIMIT_EXCEEDED");
        assertThat(count("한도초과빵")).isZero();
    }

    @Test
    void 수정은_보낸_필드만_바꾸고_설명은_빈_문자열이면_지운다() {
        Map<String, Object> request = body("잡곡빵");
        request.put("description", "고소한 빵");
        long id = id(call(HttpMethod.POST, URL, request, Role.ADMIN));

        ResponseEntity<JsonNode> response = call(HttpMethod.PATCH, URL + "/" + id,
                Map.of("price", 5000, "description", ""), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT name, price, description, display_order FROM bread WHERE id = ?", id);
        assertThat(row.get("name")).isEqualTo("잡곡빵");
        assertThat(row.get("price")).isEqualTo(5000);
        assertThat(row.get("description")).isNull();
        assertThat(row.get("display_order")).isEqualTo(30);
    }

    @Test
    void 없는_빵은_404_삭제는_405() {
        assertThat(call(HttpMethod.PATCH, URL + "/999999", Map.of("price", 5000), Role.ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> delete = call(HttpMethod.DELETE, URL + "/1", null, Role.ADMIN);

        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(delete.getHeaders().getAllow()).contains(HttpMethod.PATCH);
    }

    @Test
    void 목록은_판매_여부로_거를_수_있다() {
        long stopped = id(call(HttpMethod.POST, URL, body("잡곡빵"), Role.ADMIN));
        call(HttpMethod.PATCH, URL + "/" + stopped, Map.of("active", false), Role.ADMIN);

        JsonNode inactive = call(HttpMethod.GET, URL + "?active=false", null, Role.ADMIN).getBody();
        JsonNode all = call(HttpMethod.GET, URL, null, Role.ADMIN).getBody();

        assertThat(inactive).hasSize(1);
        assertThat(inactive.get(0).get("id").asLong()).isEqualTo(stopped);
        assertThat(all.size()).isEqualTo(SEED_BREADS + 1);
        assertThat(all.get(0).get("name").asText()).isEqualTo("소금빵");             // 노출 순서 1
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String url, Object body, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(1L, role, false));
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static Map<String, Object> body(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("category", "HARD_BREAD");
        body.put("price", 4500);
        body.put("displayOrder", 30);
        return body;
    }

    private static long id(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asLong();
    }

    private int count(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bread WHERE name = ?", Integer.class, name);
    }
}
