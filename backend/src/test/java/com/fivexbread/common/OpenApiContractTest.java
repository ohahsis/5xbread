package com.fivexbread.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 응답이 /v3/api-docs 명세와 맞는지 본다. 프론트가 이 명세로 타입을 만든다 (decisions.md 6장 "OpenAPI 명세").
 * 손님 흐름(가입 → 로그인 → 조회 → 예약 → 취소 → 탈퇴)과 사장님 조회, 대표 오류를 실제로 부르고, 응답마다:
 * 상태 코드가 명세에 있는지(오류는 default), content type, required 필드가 다 있는지, 명세에 없는 필드가 없는지,
 * null 은 nullable 필드에만 오는지, 타입·enum·날짜 형식을 검사한다.
 */
@Import(MutableClockConfig.class)
class OpenApiContractTest extends IntegrationTestSupport {

    private static final String PHONE = "01077775555";
    private static final String PASSWORD = "bread2026!";
    private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    private JsonNode docs;
    private SaleDateTestData data;
    private LocalDate date;
    private long slot;
    private Long member;
    private final List<String> checked = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mutableClock.reset();
        docs = objectMapper.readTree(restTemplate.getForObject("/v3/api-docs", String.class));
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        cleanUp();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(12);
        LocalDateTime openAt = date.minusDays(3).atTime(10, 0);
        data.saleDate(date, openAt, date.minusDays(1).atTime(20, 0), false);
        slot = data.slot(date, 11);
        data.stock(date, 1, 100, 50, 0, 4);
        data.stock(date, 3, 100, 50, 0, null);
        mutableClock.set(openAt.plusHours(1));
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        cleanUp();
    }

    @Test
    void 손님_흐름과_사장님_조회의_응답이_명세와_맞는다() {
        // 가입·로그인
        JsonNode signup = check(HttpMethod.POST, "/api/v1/members", null, Map.of("phone", PHONE, "password", PASSWORD,
                "name", "김민건", "agreements", Map.of("terms", true, "privacy", true)), HttpStatus.CREATED);
        member = signup.get("memberId").asLong();
        String token = check(HttpMethod.POST, "/api/v1/auth/tokens", null, Map.of("phone", PHONE, "password", PASSWORD),
                HttpStatus.CREATED).get("accessToken").asText();
        HttpHeaders customer = bearer(token);
        HttpHeaders admin = bearer(jwtProvider.issue(1, Role.ADMIN, false));

        // 손님 조회
        check(HttpMethod.GET, "/api/v1/sale-dates?from={from}&to={to}", customer, null, HttpStatus.OK, date.minusDays(2),
                date.plusDays(2));
        check(HttpMethod.GET, "/api/v1/sale-dates/{date}", customer, null, HttpStatus.OK, date);
        check(HttpMethod.GET, "/api/v1/sale-dates/{date}/pickup-slots", customer, null, HttpStatus.OK, date);
        JsonNode breads = check(HttpMethod.GET, "/api/v1/sale-dates/{date}/breads", customer, null, HttpStatus.OK, date);
        assertThat(breads.findValues("perUserLimit")).anyMatch(JsonNode::isNull);   // null 이 실제로 오는 경우도 검사한다
        check(HttpMethod.GET, "/api/v1/members/me", customer, null, HttpStatus.OK);
        check(HttpMethod.PATCH, "/api/v1/members/me", customer, Map.of("name", "김동건"), HttpStatus.OK);

        // 예약
        long first = reserve(customer, 3).get("reservationId").asLong();
        long second = reserve(customer, 1).get("reservationId").asLong();
        check(HttpMethod.GET, "/api/v1/members/me/reservations", customer, null, HttpStatus.OK);
        check(HttpMethod.GET, "/api/v1/reservations/{id}", customer, null, HttpStatus.OK, first);
        check(HttpMethod.GET, "/api/v1/reservations/{id}/pickup-code", customer, null, HttpStatus.OK, first);
        check(HttpMethod.PATCH, "/api/v1/reservations/{id}", customer, Map.of("status", "CANCELED"), HttpStatus.OK, first);

        // 사장님 조회
        check(HttpMethod.GET, "/api/v1/admin/sale-dates/{date}/reservations", admin, null, HttpStatus.OK, date);
        check(HttpMethod.GET, "/api/v1/admin/sale-dates/{date}/pickup-summary", admin, null, HttpStatus.OK, date);
        check(HttpMethod.GET, "/api/v1/admin/reservations/{id}", admin, null, HttpStatus.OK, first);
        check(HttpMethod.GET, "/api/v1/admin/reservations?saleDate={date}&phoneLast4=5555", admin, null, HttpStatus.OK, date);
        check(HttpMethod.GET, "/api/v1/admin/members?phone={phone}", admin, null, HttpStatus.OK, PHONE);
        check(HttpMethod.GET, "/api/v1/admin/breads", admin, null, HttpStatus.OK);
        check(HttpMethod.GET, "/api/v1/admin/sale-dates/{date}/stocks", admin, null, HttpStatus.OK, date);

        // 오류: ProblemDetail
        check(HttpMethod.POST, "/api/v1/members", null, Map.of("phone", "123"), HttpStatus.BAD_REQUEST);
        check(HttpMethod.DELETE, "/api/v1/members/me", customer, Map.of("password", PASSWORD), HttpStatus.CONFLICT);
        check(HttpMethod.DELETE, "/api/v1/members/me", customer, Map.of("password", "wrong2026!"),
                HttpStatus.UNPROCESSABLE_ENTITY);
        check(HttpMethod.GET, "/api/v1/admin/reservations/{id}", admin, null, HttpStatus.NOT_FOUND, Long.MAX_VALUE);
        check(HttpMethod.GET, "/api/v1/members/me", null, null, HttpStatus.UNAUTHORIZED);

        // 탈퇴 → 탈퇴 회원 예약의 null 필드
        check(HttpMethod.PATCH, "/api/v1/reservations/{id}", customer, Map.of("status", "CANCELED"), HttpStatus.OK, second);
        check(HttpMethod.DELETE, "/api/v1/members/me", customer, Map.of("password", PASSWORD), HttpStatus.NO_CONTENT);
        JsonNode withdrawn = check(HttpMethod.GET, "/api/v1/admin/reservations/{id}", admin, null, HttpStatus.OK, second);
        assertThat(withdrawn.get("member").get("name").isNull()).isTrue();
        check(HttpMethod.GET, "/api/v1/admin/sale-dates/{date}/reservations", admin, null, HttpStatus.OK, date);
        check(HttpMethod.POST, "/api/v1/members", null, Map.of("phone", PHONE, "password", PASSWORD, "name", "김민건",
                "agreements", Map.of("terms", true, "privacy", true)), HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(checked).hasSizeGreaterThan(25);
    }

    private JsonNode reserve(HttpHeaders customer, long breadId) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(customer);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return check(HttpMethod.POST, "/api/v1/reservations", headers, Map.of("saleDate", date.toString(),
                "pickupSlotId", slot, "items", List.of(Map.of("breadId", breadId, "quantity", 1))), HttpStatus.CREATED);
    }

    /** 호출하고 상태 코드를 확인한 뒤, 명세의 그 operation·상태 코드의 스키마로 본문을 검사한다 */
    private JsonNode check(HttpMethod method, String url, HttpHeaders headers, Object body, HttpStatus expected,
                           Object... variables) {
        HttpHeaders request = new HttpHeaders();
        if (headers != null) {
            request.addAll(headers);
        }
        if (body != null && method == HttpMethod.PATCH) {
            request.setContentType(MERGE_PATCH);
        }
        ResponseEntity<String> response = restTemplate.exchange(url, method, new HttpEntity<>(body, request),
                String.class, variables);
        String where = method + " " + url;
        assertThat(response.getStatusCode()).as(where + " " + response.getBody()).isEqualTo(expected);

        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        JsonNode operation = operation(path, method);
        assertThat(operation).as(where + " 이 명세에 없다").isNotNull();
        String code = String.valueOf(expected.value());
        JsonNode documented = operation.get("responses").get(code);
        if (documented == null) {
            assertThat(expected.isError()).as(where + " 성공 코드 " + code + " 가 명세에 없다").isTrue();
            documented = operation.get("responses").get("default");
        }
        checked.add(where + " " + code);
        if (response.getBody() == null || response.getBody().isEmpty()) {
            assertThat(documented.has("content")).as(where + " 본문이 없는데 명세에는 있다").isFalse();
            return null;
        }
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).as(where).isNotNull();
        String mediaType = contentType.getType() + "/" + contentType.getSubtype();
        JsonNode schema = documented.path("content").path(mediaType).path("schema");
        assertThat(schema.isMissingNode()).as(where + " 명세에 " + mediaType + " 응답이 없다: " + documented).isFalse();
        try {
            JsonNode json = objectMapper.readTree(response.getBody());
            validate(schema, json, where + " $");
            return json;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode operation(String path, HttpMethod method) {
        Iterator<Map.Entry<String, JsonNode>> paths = docs.get("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> entry = paths.next();
            String pattern = entry.getKey().replaceAll("\\{[^}]+}", "[^/]+");
            if (path.replaceAll("\\{[^}]+}", "x").matches(pattern)) {
                return entry.getValue().get(method.name().toLowerCase());
            }
        }
        return null;
    }

    // JSON Schema(OpenAPI 3.1) 중 이 프로젝트 응답에 쓰이는 부분만: $ref, type(배열 포함), required, properties,
    // additionalProperties, items, enum, format(date, date-time)
    private void validate(JsonNode schema, JsonNode value, String at) {
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            validate(docs.get("components").get("schemas").get(ref.substring(ref.lastIndexOf('/') + 1)), value, at);
            return;
        }
        List<String> types = new ArrayList<>();
        if (schema.has("type")) {
            if (schema.get("type").isArray()) {
                schema.get("type").forEach(type -> types.add(type.asText()));
            } else {
                types.add(schema.get("type").asText());
            }
        }
        if (value.isNull()) {
            assertThat(types).as(at + " 가 null 인데 nullable 이 아니다").contains("null");
            return;
        }
        if (schema.has("enum")) {
            List<JsonNode> allowed = new ArrayList<>();
            schema.get("enum").forEach(allowed::add);
            assertThat(allowed).as(at + " enum").contains(value);
        }
        String type = types.stream().filter(t -> !t.equals("null")).findFirst().orElse(null);
        if (type == null) {
            return;                                                     // 모양을 정하지 않은 값(additionalProperties 등)
        }
        switch (type) {
            case "object" -> {
                assertThat(value.isObject()).as(at + " object").isTrue();
                JsonNode properties = schema.path("properties");
                schema.path("required").forEach(name ->
                        assertThat(value.has(name.asText())).as(at + "." + name.asText() + " 는 required 인데 없다").isTrue());
                value.fieldNames().forEachRemaining(name -> {
                    if (properties.has(name)) {
                        validate(properties.get(name), value.get(name), at + "." + name);
                    } else {
                        JsonNode additional = schema.get("additionalProperties");
                        if (additional == null || additional.isBoolean() && !additional.asBoolean()) {
                            fail(at + "." + name + " 가 명세에 없다");
                        }
                        if (additional.isObject()) {
                            validate(additional, value.get(name), at + "." + name);
                        }
                    }
                });
            }
            case "array" -> {
                assertThat(value.isArray()).as(at + " array").isTrue();
                for (int i = 0; i < value.size(); i++) {
                    validate(schema.get("items"), value.get(i), at + "[" + i + "]");
                }
            }
            case "string" -> {
                assertThat(value.isTextual()).as(at + " string: " + value).isTrue();
                String format = schema.path("format").asText("");
                if (format.equals("date-time")) {
                    OffsetDateTime.parse(value.asText());
                } else if (format.equals("date")) {
                    LocalDate.parse(value.asText());
                }
            }
            case "integer" -> assertThat(value.isIntegralNumber()).as(at + " integer: " + value).isTrue();
            case "number" -> assertThat(value.isNumber()).as(at + " number: " + value).isTrue();
            case "boolean" -> assertThat(value.isBoolean()).as(at + " boolean: " + value).isTrue();
            default -> fail(at + " 알 수 없는 type " + type);
        }
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private void cleanUp() {
        data.clean();
        jdbcTemplate.update("DELETE FROM member_agreement WHERE member_id IN (SELECT id FROM member WHERE phone = ?)", PHONE);
        jdbcTemplate.update("DELETE FROM member WHERE phone = ?", PHONE);
        if (member != null) {
            jdbcTemplate.update("DELETE FROM member_agreement WHERE member_id = ?", member);
            jdbcTemplate.update("DELETE FROM member WHERE id = ?", member);
        }
    }
}
