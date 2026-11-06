package com.fivexbread.saledate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 빵 목록의 Redis 잔여·로컬 캐시·무효화 (C-02, 13-4). 다른 테스트와 달리 캐시를 실제 값(30초)으로 켠다.
 * 시계는 오픈 1시간 뒤로 고정한다. 빵 1: 잔여 30·한도 4, 빵 2: 매진, 빵 3: 잔여 20·한도 2.
 */
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "fivexbread.catalog-cache.ttl=30s")
class SaleBreadsCacheApiTest extends IntegrationTestSupport {

    private static final String RESERVE_URL = "/api/v1/reservations";

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    SaleCatalogCache catalogCache;

    @Autowired
    RedisMessageListenerContainer listenerContainer;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime openAt;
    private LocalDateTime now;
    private long slot;
    private int price1;
    private int price3;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(15);
        openAt = date.minusDays(3).atTime(10, 0);
        data.saleDate(date, openAt, date.minusDays(1).atTime(20, 0), false);
        data.stock(date, 1, 100, 60, 30, 4);
        data.stock(date, 2, 80, 50, 50, null);
        data.stock(date, 3, 40, 20, 0, 2);
        slot = data.slot(date, 11);
        price1 = price(1);
        price3 = price(3);
        now = openAt.plusHours(1);
        mutableClock.set(now);
        catalogCache.evictAll();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("UPDATE bread SET price = ? WHERE id = 1", price1);
        jdbcTemplate.update("UPDATE bread SET price = ? WHERE id = 3", price3);
        mutableClock.reset();
        data.clean();
        catalogCache.evictAll();
    }

    @Test
    void 예약_후_목록_잔여가_Redis_값과_같고_내_예약_수량도_보인다() {
        long member = data.memberId();

        assertThat(reserve(member, 3, 2).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode bread = bread(list(member), 3);
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 3))).isEqualTo("18");
        assertThat(bread.get("remaining").asInt()).isEqualTo(18);
        assertThat(bread.get("myReservedQty").asInt()).isEqualTo(2);
    }

    @Test
    void 잔여는_DB가_아니라_Redis_값이고_매진_순서도_Redis_기준이다() {
        redis.opsForValue().set(StockKeys.stock(date, 1), "0");
        redis.opsForValue().set(StockKeys.stock(date, 2), "7");
        redis.opsForValue().set(StockKeys.stock(date, 3), "5");

        JsonNode breads = list(null).get("breads");

        assertThat(ids(breads)).containsExactly(2L, 3L, 1L);
        assertThat(bread(breads, 1).get("status").asText()).isEqualTo("SOLD_OUT");
        assertThat(bread(breads, 2).get("remaining").asInt()).isEqualTo(7);
        assertThat(bread(breads, 2).get("status").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    void 키가_없으면_DB_기준으로_응답하고_키를_적재한다() {
        JsonNode breads = list(null).get("breads");

        assertThat(bread(breads, 1).get("remaining").asInt()).isEqualTo(30);
        assertThat(bread(breads, 2).get("remaining").asInt()).isZero();
        assertThat(bread(breads, 3).get("remaining").asInt()).isEqualTo(20);
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 1))).isEqualTo("30");
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 2))).isEqualTo("0");
        assertThat(redis.getExpire(StockKeys.stock(date, 3))).isPositive();
    }

    @Test
    void 일부_키만_없으면_있는_키는_Redis_없는_키는_DB이고_적재가_있던_값을_덮지_않는다() {
        redis.opsForValue().set(StockKeys.stock(date, 3), "5");     // 첫 번째 키(빵 1)가 없는 MGET

        JsonNode breads = list(null).get("breads");

        assertThat(bread(breads, 1).get("remaining").asInt()).isEqualTo(30);
        assertThat(bread(breads, 3).get("remaining").asInt()).isEqualTo(5);
        assertThat(redis.opsForValue().get(StockKeys.stock(date, 3))).isEqualTo("5");
    }

    @Test
    void 오픈_전에는_적재하고_마감_뒤에는_적재하지_않는다() {
        mutableClock.set(openAt.minusHours(1));
        assertThat(list(null).get("status").asText()).isEqualTo("NOT_OPEN");
        assertThat(redis.hasKey(StockKeys.stock(date, 1))).isTrue();

        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        mutableClock.set(date.minusDays(1).atTime(20, 0));
        JsonNode closed = list(null);

        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(bread(closed.get("breads"), 1).get("remaining").asInt()).isEqualTo(30);
        assertThat(redis.hasKey(StockKeys.stock(date, 1))).isFalse();
    }

    @Test
    void 내_예약_수량은_비로그인_null_해시가_없으면_DB_있으면_해시이고_gen_count는_나가지_않는다() {
        long member = data.memberId();
        long id = data.reservation(date, slot, "RESERVED");
        data.item(id, 1, 2);

        assertThat(bread(list(null), 1).get("myReservedQty").isNull()).isTrue();
        assertThat(bread(list(member), 1).get("myReservedQty").asInt()).isEqualTo(2);     // 해시 없음 → DB
        assertThat(bread(list(member), 3).get("myReservedQty").asInt()).isZero();

        String key = StockKeys.userQty(date, member);
        redis.opsForHash().putAll(key, Map.of("1", "3", "count", "1", "gen", "secret-gen"));
        JsonNode body = list(member);

        assertThat(bread(body, 1).get("myReservedQty").asInt()).isEqualTo(3);             // 해시가 먼저
        assertThat(bread(body, 3).get("myReservedQty").asInt()).isZero();
        assertThat(body.toString()).doesNotContain("secret-gen").doesNotContain("count");
    }

    @Test
    void count_없는_해시는_적재된_것이_아니라_DB를_본다() {
        long member = data.memberId();
        long id = data.reservation(date, slot, "RESERVED");
        data.item(id, 1, 2);
        redis.opsForHash().put(StockKeys.userQty(date, member), "1", "9");

        assertThat(bread(list(member), 1).get("myReservedQty").asInt()).isEqualTo(2);
    }

    @Test
    void 캐시는_30초_동안_DB_변경을_보지_않고_Pub_Sub_없이도_TTL_뒤_반영된다() {
        list(null);
        jdbcTemplate.update("UPDATE bread SET price = 9900 WHERE id = 1");
        jdbcTemplate.update("UPDATE daily_stock SET per_user_limit = 1 WHERE sale_date = ? AND bread_id = 1", date);

        mutableClock.set(now.plusSeconds(29));
        assertThat(bread(list(null), 1).get("price").asInt()).isEqualTo(price1);

        mutableClock.set(now.plusSeconds(31));
        JsonNode bread = bread(list(null), 1);
        assertThat(bread.get("price").asInt()).isEqualTo(9900);
        assertThat(bread.get("perUserLimit").asInt()).isEqualTo(1);
    }

    @Test
    void 판매일_설정도_30초_캐시이고_TTL_뒤_반영된다() {
        list(null);
        jdbcTemplate.update("UPDATE sale_date SET close_at = ? WHERE sale_date = ?", now, date);

        assertThat(list(null).get("status").asText()).isEqualTo("OPEN");
        mutableClock.set(now.plusSeconds(31));
        assertThat(list(null).get("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    void 사장님이_수량을_저장하면_목록에_바로_반영된다() {
        list(null);
        HttpHeaders headers = adminHeaders();
        headers.setIfMatch(restTemplate.exchange(stocksUrl(), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class)
                .getHeaders().getETag());

        ResponseEntity<JsonNode> saved = restTemplate.exchange(stocksUrl(), HttpMethod.PUT,
                new HttpEntity<>(List.of(stockRow(1, 100, 70, 3)), headers), JsonNode.class);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode bread = bread(list(null), 1);
        assertThat(bread.get("perUserLimit").asInt()).isEqualTo(3);
        assertThat(bread.get("remaining").asInt()).isEqualTo(40);
    }

    @Test
    void 사장님이_빵을_저장하면_목록에_바로_반영되고_판매_중지도_빠진다() {
        list(null);

        assertThat(patchBread(1, Map.of("price", 3600)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bread(list(null), 1).get("price").asInt()).isEqualTo(3600);

        assertThat(patchBread(3, Map.of("active", false)).getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            assertThat(ids(list(null).get("breads"))).containsExactly(1L, 2L);
        } finally {
            patchBread(3, Map.of("active", true));
        }
    }

    @Test
    void 사장님이_판매일을_저장하면_상태에_바로_반영되고_없던_판매일도_바로_보인다() {
        list(null);
        Map<String, Object> closeNow = saleDateBody(date, openAt, now, false, List.of("11:00"));

        assertThat(putSaleDate(date, closeNow).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list(null).get("status").asText()).isEqualTo("CLOSED");

        LocalDate next = date.plusDays(1);
        assertThat(get(next).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);      // 없는 날도 캐시된다
        assertThat(putSaleDate(next, saleDateBody(next, next.minusDays(3).atTime(10, 0), next.minusDays(1).atTime(20, 0),
                false, List.of("11:00"))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get(next).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 실패한_저장은_캐시를_비우지_않는다() {
        list(null);
        jdbcTemplate.update("UPDATE bread SET price = 9900 WHERE id = 1");

        assertThat(patchBread(1, Map.of("name", "크로와상")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(bread(list(null), 1).get("price").asInt()).isEqualTo(price1);
    }

    @Test
    void 다른_앱의_무효화_메시지를_받으면_캐시를_비운다() {
        list(null);
        jdbcTemplate.update("UPDATE bread SET price = 9900 WHERE id = 1");

        redis.convertAndSend(CatalogCacheEvictor.CHANNEL, date.toString());

        await().atMost(Duration.ofSeconds(5))
                .until(() -> bread(list(null), 1).get("price").asInt() == 9900);

        jdbcTemplate.update("UPDATE bread SET price = 9800 WHERE id = 1");
        redis.convertAndSend(CatalogCacheEvictor.CHANNEL, "*");
        await().atMost(Duration.ofSeconds(5))
                .until(() -> bread(list(null), 1).get("price").asInt() == 9800);
    }

    @Test
    void 저장하면_다른_앱을_위해_채널에_발행한다() {
        List<String> received = new CopyOnWriteArrayList<>();
        MessageListener listener = (message, pattern) ->
                received.add(new String(message.getBody(), StandardCharsets.UTF_8));
        ChannelTopic topic = ChannelTopic.of(CatalogCacheEvictor.CHANNEL);
        listenerContainer.addMessageListener(listener, topic);
        try {
            putSaleDate(date, saleDateBody(date, openAt, now, false, List.of("11:00")));
            patchBread(1, Map.of("price", 3600));

            await().atMost(Duration.ofSeconds(5)).until(() -> received.containsAll(List.of(date.toString(), "*")));
        } finally {
            listenerContainer.removeMessageListener(listener, topic);
        }
    }

    @Test
    void 휴무일로_바꾸면_적재된_재고_키도_지워_옛_잔여가_살아나지_않는다() {
        mutableClock.set(openAt.minusHours(1));
        list(null);
        assertThat(redis.hasKey(StockKeys.stock(date, 1))).isTrue();

        Map<String, Object> holiday = saleDateBody(date, openAt, date.minusDays(1).atTime(20, 0), true, List.of());
        assertThat(putSaleDate(date, holiday).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(redis.hasKey(StockKeys.stock(date, 1))).isFalse();
        JsonNode body = list(null);
        assertThat(body.get("status").asText()).isEqualTo("HOLIDAY");
        assertThat(body.get("breads")).isEmpty();
    }

    @Test
    void 예약_금액은_캐시가_아니라_DB_가격이다() {
        list(null);
        jdbcTemplate.update("UPDATE bread SET price = 5000 WHERE id = 3");
        assertThat(bread(list(null), 3).get("price").asInt()).isEqualTo(price3);    // 목록은 아직 캐시

        ResponseEntity<JsonNode> created = reserve(data.memberId(), 3, 2);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("totalPrice").asInt()).isEqualTo(10_000);
        long id = created.getBody().get("reservationId").asLong();
        assertThat(jdbcTemplate.queryForObject("SELECT unit_price FROM reservation_item WHERE reservation_id = ?",
                Integer.class, id)).isEqualTo(5000);
    }

    private ResponseEntity<JsonNode> reserve(long memberId, long breadId, int quantity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, Role.CUSTOMER, false));
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("saleDate", date.toString(), "pickupSlotId", slot,
                "items", List.of(Map.of("breadId", breadId, "quantity", quantity)));
        return restTemplate.exchange(RESERVE_URL, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode list(Long memberId) {
        HttpHeaders headers = new HttpHeaders();
        if (memberId != null) {
            headers.setBearerAuth(jwtProvider.issue(memberId, Role.CUSTOMER, false));
        }
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/v1/sale-dates/{date}/breads", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class, date);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> get(LocalDate target) {
        return restTemplate.getForEntity("/api/v1/sale-dates/{date}/breads", JsonNode.class, target);
    }

    private static JsonNode bread(JsonNode bodyOrBreads, long breadId) {
        JsonNode breads = bodyOrBreads.has("breads") ? bodyOrBreads.get("breads") : bodyOrBreads;
        for (JsonNode bread : breads) {
            if (bread.get("breadId").asLong() == breadId) {
                return bread;
            }
        }
        throw new AssertionError("목록에 없는 빵 " + breadId);
    }

    private static List<Long> ids(JsonNode breads) {
        List<Long> ids = new ArrayList<>();
        breads.forEach(bread -> ids.add(bread.get("breadId").asLong()));
        return ids;
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(1L, Role.ADMIN, false));
        return headers;
    }

    private String stocksUrl() {
        return "/api/v1/admin/sale-dates/" + date + "/stocks";
    }

    private ResponseEntity<JsonNode> patchBread(long breadId, Map<String, Object> body) {
        return restTemplate.exchange("/api/v1/admin/breads/{id}", HttpMethod.PATCH, new HttpEntity<>(body, adminHeaders()),
                JsonNode.class, breadId);
    }

    private ResponseEntity<JsonNode> putSaleDate(LocalDate target, Map<String, Object> body) {
        return restTemplate.exchange("/api/v1/admin/sale-dates/{date}", HttpMethod.PUT,
                new HttpEntity<>(body, adminHeaders()), JsonNode.class, target);
    }

    private static Map<String, Object> saleDateBody(LocalDate target, LocalDateTime open, LocalDateTime close,
                                                    boolean holiday, List<String> pickupTimes) {
        return Map.of("openAt", open + ":00+09:00", "closeAt", close + ":00+09:00", "holiday", holiday,
                "pickupTimes", pickupTimes);
    }

    private static Map<String, Object> stockRow(long breadId, int produced, int reservable, Integer limit) {
        Map<String, Object> row = new HashMap<>();
        row.put("breadId", breadId);
        row.put("producedQty", produced);
        row.put("reservableQty", reservable);
        row.put("perUserLimit", limit);
        return row;
    }

    private int price(long breadId) {
        return jdbcTemplate.queryForObject("SELECT price FROM bread WHERE id = ?", Integer.class, breadId);
    }
}
