package com.fivexbread.saledate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.core.SimpleLock;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 수량 재계산 (A-12). 판매일은 20일 뒤(예약 중). DB 기준 잔여: 빵 1 = 40, 빵 2 = 30, 빵 3 = 20.
 * 테스트 프로필은 두 관측 간격이 0초이고, 두 관측 사이의 변화는 observe 스파이로 만든다.
 */
@Import(MutableClockConfig.class)
class StockReconciliationApiTest extends IntegrationTestSupport {

    private static final long ADMIN_ID = 9_000_002L;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    @MockitoSpyBean
    StockReconciler reconciler;

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        jdbcTemplate.update("DELETE FROM admin_action_log WHERE action = 'STOCK_RECONCILED'");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        now = data.today().atTime(12, 0);
        date = data.today().plusDays(20);
        data.saleDate(date, now.minusHours(1), date.minusDays(1).atTime(20, 0), false);
        data.stock(date, 1, 100, 50, 10, null);
        data.stock(date, 2, 100, 30, 0, null);
        data.stock(date, 3, 100, 20, 0, null);
        redis.opsForValue().set(StockKeys.stock(date, 1), "35");
        redis.opsForValue().set(StockKeys.stock(date, 2), "30");
        mutableClock.set(now);
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
        jdbcTemplate.update("DELETE FROM admin_action_log WHERE action = 'STOCK_RECONCILED'");
    }

    @Test
    void 바로_보정하고_201로_빵별_이전_값_DB_기준_값_보정량을_돌려주며_기록을_남긴다() throws Exception {
        ResponseEntity<JsonNode> response = post(date, Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body.get("saleDate").asText()).isEqualTo(date.toString());
        assertThat(body.get("reconciledAt").asText()).isEqualTo(now.toLocalDate() + "T12:00:00+09:00");
        assertThat(rows(body)).containsExactly(
                "1:35:40:5:ADJUSTED",
                "2:30:30:0:MATCHED",
                "3:null:20:0:LOADED");                            // 키가 없으면 DB 기준으로 적재
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 1))).isEqualTo("40");
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 3))).isEqualTo("20");

        Map<String, Object> log = jdbcTemplate.queryForMap(
                "SELECT id, admin_id, target_type, target_id, detail FROM admin_action_log WHERE action = 'STOCK_RECONCILED'");
        assertThat(((Number) log.get("id")).longValue()).isEqualTo(body.get("reconciliationId").asLong());
        assertThat(log.get("admin_id")).isEqualTo(ADMIN_ID);
        assertThat(log.get("target_type")).isEqualTo("SALE_DATE");
        assertThat(log.get("target_id")).isEqualTo(date.toString());
        assertThat(rows(objectMapper.readTree(log.get("detail").toString()))).hasSize(3).contains("1:35:40:5:ADJUSTED");
    }

    @Test
    void 두_관측_사이에_차이가_바뀐_빵은_진행_중으로_보고_보정하지_않는다() {
        int[] calls = {0};
        doAnswer(invocation -> {
            if (++calls[0] == 2) {
                redis.opsForValue().decrement(StockKeys.stock(date, 1));          // 그사이 예약이 Redis 에서 1개 뺌
            }
            return invocation.callRealMethod();
        }).when(reconciler).observe(any());

        ResponseEntity<JsonNode> response = post(date, Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rows(response.getBody())).contains("1:34:40:0:CHANGING");
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 1))).isEqualTo("34");
    }

    @Test
    void 마감이_지난_판매일은_있는_키만_보정하고_없는_키는_만들지_않는다() {
        mutableClock.set(date.minusDays(1).atTime(20, 0));

        ResponseEntity<JsonNode> response = post(date, Role.ADMIN);

        assertThat(rows(response.getBody())).containsExactly("1:35:40:5:ADJUSTED", "2:30:30:0:MATCHED",
                "3:null:20:0:NO_KEY");
        assertThat(redis.hasKey(StockKeys.stock(date, 3))).isFalse();
    }

    @Test
    void 정기_점검의_첫_관측을_지워_보정_뒤_옛_차이로_다시_보정하지_않는다() {
        reconciler.runScheduled();                                         // 차이 5 를 1회 관측
        assertThat(redis.hasKey(StockKeys.reconcileSeen(date, 1))).isTrue();

        post(date, Role.ADMIN);

        assertThat(redis.hasKey(StockKeys.reconcileSeen(date, 1))).isFalse();
    }

    @Test
    void 손님은_403_없는_판매일은_404_휴무일은_422_같은_판매일_재계산_중이면_409() {
        assertThat(post(date, Role.CUSTOMER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertCode(post(date.plusDays(30), Role.ADMIN), HttpStatus.NOT_FOUND, "NOT_FOUND");

        LocalDate holiday = date.plusDays(1);
        data.saleDate(holiday, now.minusHours(1), holiday.minusDays(1).atTime(20, 0), true);
        assertCode(post(holiday, Role.ADMIN), HttpStatus.UNPROCESSABLE_ENTITY, "SALE_DATE_HOLIDAY");

        SimpleLock lock = reconciler.tryLock(date).orElseThrow();
        try {
            assertCode(post(date, Role.ADMIN), HttpStatus.CONFLICT, "RECONCILIATION_IN_PROGRESS");
        } finally {
            lock.unlock();
        }
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 1))).isEqualTo("35");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE action = 'STOCK_RECONCILED'",
                Integer.class)).isZero();
    }

    private ResponseEntity<JsonNode> post(LocalDate saleDate, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(role == Role.ADMIN ? ADMIN_ID : data.memberId(), role, false));
        return restTemplate.exchange("/api/v1/admin/sale-dates/" + saleDate + "/stock-reconciliations",
                HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
    }

    private static List<String> rows(JsonNode body) {
        List<String> rows = new ArrayList<>();
        body.get("breads").forEach(b -> rows.add(b.get("breadId").asText() + ":" + b.get("redisBefore").asText() + ":"
                + b.get("dbRemaining").asText() + ":" + b.get("adjustment").asText() + ":" + b.get("result").asText()));
        return rows;
    }

    private static void assertCode(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }
}
