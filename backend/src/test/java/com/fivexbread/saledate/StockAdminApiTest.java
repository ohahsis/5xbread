package com.fivexbread.saledate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 판매일별 수량 조회(ETag)·일괄 저장(If-Match) (A-02, R-01·R-07, decisions.md 6장).
 * 시드 빵 1·2·3(판매 중)을 쓰고, 날짜는 시드와 겹치지 않는 25일 뒤.
 */
class StockAdminApiTest extends IntegrationTestSupport {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    Clock clock;

    @MockitoSpyBean
    DailyStockRepository dailyStockRepository;                  // 검사와 UPDATE 사이에 예약이 끼어드는 상황을 만든다

    private SaleDateTestData data;
    private LocalDate date;
    private String url;

    @BeforeEach
    void setUp() {
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        date = data.today().plusDays(25);
        url = "/api/v1/admin/sale-dates/" + date + "/stocks";
        data.saleDate(date);
        data.stock(date, 1, 100, 60, 30, 4);       // 소금빵: 예약 30
        data.stock(date, 2, 80, 50, 10, null);     // 크로와상: 예약 10
    }

    @Test
    void 손님은_조회도_저장도_403() {
        assertThat(get(Role.CUSTOMER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put("\"v0\"", List.of(row(1, 100, 70, 4)), Role.CUSTOMER).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reservable(1)).isEqualTo(60);
    }

    @Test
    void 조회하면_판매_중인_빵_전부와_ETag() {
        ResponseEntity<JsonNode> response = get(Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"v0\"");
        JsonNode rows = response.getBody().get("rows");
        long active = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bread WHERE active", Long.class);
        assertThat(rows.size()).isEqualTo(active);
        JsonNode salt = rows.get(0);
        assertThat(salt.get("breadId").asLong()).isEqualTo(1);
        assertThat(salt.get("configured").asBoolean()).isTrue();
        assertThat(salt.get("reservedQty").asInt()).isEqualTo(30);
        JsonNode notConfigured = rows.get(2);                     // 빵 3: 이 날 수량 행 없음
        assertThat(notConfigured.get("configured").asBoolean()).isFalse();
        assertThat(notConfigured.get("perUserLimit").isNull()).isTrue();
    }

    @Test
    void If_Match가_없으면_428() {
        ResponseEntity<JsonNode> response = put(null, List.of(row(1, 100, 70, 4)), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PRECONDITION_REQUIRED");
        assertThat(reservable(1)).isEqualTo(60);
    }

    @Test
    void If_Match가_현재_버전과_다르거나_형식이_다르면_412() {
        for (String ifMatch : List.of("\"v5\"", "v0", "W/\"v0\"", "*")) {
            ResponseEntity<JsonNode> response = put(ifMatch, List.of(row(1, 100, 70, 4)), Role.ADMIN);
            assertThat(response.getStatusCode()).as(ifMatch).isEqualTo(HttpStatus.PRECONDITION_FAILED);
            assertThat(response.getBody().get("code").asText()).isEqualTo("PRECONDITION_FAILED");
        }
        assertThat(reservable(1)).isEqualTo(60);
        assertThat(data.version(date)).isZero();
    }

    @Test
    void 저장하면_200_새_ETag이고_없던_행은_만들며_같은_ETag로_다시_저장하면_412() {
        ResponseEntity<JsonNode> response = put("\"v0\"",
                List.of(row(1, 120, 80, 5), row(3, 40, 20, null)), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"v1\"");
        assertThat(data.version(date)).isEqualTo(1);
        Map<String, Object> salt = stock(1);
        assertThat(salt.get("produced_qty")).isEqualTo(120);
        assertThat(salt.get("reservable_qty")).isEqualTo(80);
        assertThat(salt.get("reserved_qty")).isEqualTo(30);       // 예약 수량은 그대로
        assertThat(salt.get("per_user_limit")).isEqualTo(5);
        assertThat(stock(3).get("reservable_qty")).isEqualTo(20);
        assertThat(reservable(2)).isEqualTo(50);                  // 요청에 없는 행은 그대로

        assertThat(put("\"v0\"", List.of(row(1, 120, 90, 5)), Role.ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(reservable(1)).isEqualTo(80);
    }

    @Test
    void 예약_수량보다_적게_줄이는_등_틀린_행이_있으면_422와_틀린_행_전부이고_아무것도_저장하지_않는다() {
        jdbcTemplate.update("UPDATE bread SET active = FALSE WHERE id = 4");
        try {
            ResponseEntity<JsonNode> response = put("\"v0\"", List.of(
                    row(1, 100, 20, 4),          // 예약 30 > 총량 20
                    row(2, 80, 5, 30),           // 예약 10 > 총량 5, 한도 30
                    row(3, 40, 50, null),        // 총량 > 생산량 (R-01). 이 행만 보면 나머지는 맞음
                    row(4, 10, 5, null),         // 판매 중지된 빵을 새로 설정
                    row(5, 600, 10, null),       // 생산량 500 초과
                    row(999_999, 10, 5, null)), // 없는 빵
                    Role.ADMIN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_STOCK_ROWS");
            assertThat(errors(response)).containsExactlyInAnyOrder(
                    "1:reservableQty:STOCK_BELOW_RESERVED:30",
                    "2:reservableQty:STOCK_BELOW_RESERVED:10",
                    "2:perUserLimit:OUT_OF_RANGE",
                    "3:reservableQty:EXCEEDS_PRODUCED",
                    "4:breadId:BREAD_INACTIVE",
                    "5:producedQty:OUT_OF_RANGE",
                    "999999:breadId:BREAD_NOT_FOUND");
        } finally {
            jdbcTemplate.update("UPDATE bread SET active = TRUE WHERE id = 4");
        }
        assertThat(reservable(1)).isEqualTo(60);
        assertThat(reservable(2)).isEqualTo(50);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM daily_stock WHERE sale_date = ?",
                Integer.class, date)).isEqualTo(2);
        assertThat(data.version(date)).isZero();
    }

    @Test
    void 한_행만_틀려도_맞는_행까지_아무것도_저장하지_않는다() {
        ResponseEntity<JsonNode> response = put("\"v0\"", List.of(row(1, 100, 90, 4), row(2, 80, 81, null)), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errors(response)).containsExactly("2:reservableQty:EXCEEDS_PRODUCED");
        assertThat(reservable(1)).isEqualTo(60);
        assertThat(data.version(date)).isZero();
    }

    @Test
    void 검사_뒤_UPDATE_전에_예약이_들어와_총량보다_많아지면_422이고_버전까지_전부_롤백() {
        // 저장이 수량을 읽어 검사를 통과한 직후, 다른 트랜잭션의 예약이 소금빵 예약 수량을 30 → 50 으로 올리고 커밋한다
        // 리포지토리는 인터페이스 프록시라 callRealMethod 를 못 쓴다. 스파이의 기본 응답(원래 빈에 위임)을 그대로 부른다
        Answer<?> real = Mockito.mockingDetails(dailyStockRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object stocks = real.answer(invocation);
            CompletableFuture.runAsync(() -> jdbcTemplate.update(
                    "UPDATE daily_stock SET reserved_qty = 50 WHERE sale_date = ? AND bread_id = 1", date)).join();
            return stocks;
        }).doAnswer(real)
                .when(dailyStockRepository).findBySaleDate(any());

        ResponseEntity<JsonNode> response = put("\"v0\"", List.of(row(2, 80, 70, null), row(1, 100, 40, 4)), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errors(response)).containsExactly("1:reservableQty:STOCK_BELOW_RESERVED:50");
        assertThat(reservable(2)).isEqualTo(50);                  // 먼저 UPDATE 된 크로와상도 롤백
        assertThat(reservable(1)).isEqualTo(60);
        assertThat(data.version(date)).isZero();
    }

    @Test
    void 같은_ETag로_동시에_저장하면_하나만_성공하고_나머지는_412() throws Exception {
        int requests = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            int reservable = 61 + i;                              // 요청마다 다른 값
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return HttpStatus.valueOf(put("\"v0\"", List.of(row(1, 100, reservable, 4)), Role.ADMIN)
                        .getStatusCode().value());
            }));
        }
        ready.await();
        start.countDown();
        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<HttpStatus> result : results) {
            statuses.add(result.get(60, TimeUnit.SECONDS));
        }
        executor.shutdown();

        assertThat(statuses).filteredOn(HttpStatus.OK::equals).hasSize(1);
        assertThat(statuses).filteredOn(HttpStatus.PRECONDITION_FAILED::equals).hasSize(requests - 1);
        assertThat(data.version(date)).isEqualTo(1);
        assertThat(reservable(1)).isBetween(61, 65);
    }

    @Test
    void 휴무일은_422_없는_판매일은_404() {
        LocalDate holiday = date.plusDays(1);
        data.saleDate(holiday, holiday.minusDays(3).atTime(10, 0), holiday.minusDays(1).atTime(20, 0), true);

        ResponseEntity<JsonNode> response = exchange(HttpMethod.PUT, "/api/v1/admin/sale-dates/" + holiday + "/stocks",
                "\"v0\"", List.of(row(1, 100, 60, 4)), Role.ADMIN);
        ResponseEntity<JsonNode> missing = exchange(HttpMethod.GET,
                "/api/v1/admin/sale-dates/" + date.plusDays(2) + "/stocks", null, null, Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SALE_DATE_HOLIDAY");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<JsonNode> get(Role role) {
        return exchange(HttpMethod.GET, url, null, null, role);
    }

    private ResponseEntity<JsonNode> put(String ifMatch, List<Map<String, Object>> rows, Role role) {
        return exchange(HttpMethod.PUT, url, ifMatch, rows, role);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String target, String ifMatch, Object body, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(1L, role, false));
        if (ifMatch != null) {
            headers.set(HttpHeaders.IF_MATCH, ifMatch);
        }
        return restTemplate.exchange(target, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static Map<String, Object> row(long breadId, int produced, int reservable, Integer limit) {
        Map<String, Object> row = new HashMap<>();
        row.put("breadId", breadId);
        row.put("producedQty", produced);
        row.put("reservableQty", reservable);
        row.put("perUserLimit", limit);
        return row;
    }

    // "breadId:field:reason[:reserved]"
    private static List<String> errors(ResponseEntity<JsonNode> response) {
        List<String> errors = new ArrayList<>();
        response.getBody().get("errors").forEach(e -> errors.add(e.get("breadId").asText() + ":" + e.get("field").asText()
                + ":" + e.get("reason").asText() + (e.has("reserved") ? ":" + e.get("reserved").asInt() : "")));
        return errors;
    }

    private Map<String, Object> stock(long breadId) {
        return jdbcTemplate.queryForMap("SELECT * FROM daily_stock WHERE sale_date = ? AND bread_id = ?", date, breadId);
    }

    private int reservable(long breadId) {
        return (int) stock(breadId).get("reservable_qty");
    }
}
