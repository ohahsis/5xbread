package com.fivexbread.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.realtime.StockMessage.Change;
import com.fivexbread.reservation.ReservationStockStore;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.saledate.StockKeys;
import com.fivexbread.saledate.StockRedisSync;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * 잔여 실시간 갱신 (C-03, 13-3). 실제 STOMP 클라이언트로 /ws 에 붙어 구독한 뒤 예약·취소·수량 저장·보정을 일으키고,
 * 받은 메시지의 잔여가 Redis 와 같은지, 버전이 판매일 stockver 와 같이 올라가는지 본다.
 * 시드 빵 1(총량 30)·3(총량 50), 날짜는 14일 뒤.
 */
@Import(MutableClockConfig.class)
class StockRealtimeApiTest extends IntegrationTestSupport {

    private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

    @Autowired
    StockRedisSync stockRedisSync;

    @Autowired
    ReservationStockStore stockStore;

    private SaleDateTestData data;
    private LocalDate date;
    private LocalDateTime now;
    private long slot;
    private String topic;
    private final List<StompTestClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(14);
        LocalDateTime openAt = date.minusDays(3).atTime(10, 0);
        data.saleDate(date, openAt, date.minusDays(1).atTime(20, 0), false);
        slot = data.slot(date, 11);
        data.stock(date, 1, 100, 30, 0, null);
        data.stock(date, 3, 100, 50, 0, null);
        now = openAt.plusHours(1);
        mutableClock.set(now);
        topic = "/topic/sale-dates/" + date + "/stock";
    }

    @AfterEach
    void tearDown() {
        clients.forEach(StompTestClient::close);
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 예약하면_구독자가_바뀐_빵의_잔여와_버전을_받는다() throws Exception {
        BlockingQueue<StockMessage> messages = connect(null).subscribe(topic);

        reserve(data.memberId(), Map.of(3L, 3));

        StockMessage message = next(messages);
        assertThat(message.changes()).containsExactly(new Change(3, 47));
        assertThat(message.version()).isEqualTo(1);
        assertThat(redisStock(3)).isEqualTo(47);
        assertThat(redisVersion()).isEqualTo(1);
    }

    @Test
    void 여러_빵을_한_번에_예약하면_한_메시지에_두_빵이_같은_버전으로_온다() throws Exception {
        BlockingQueue<StockMessage> messages = connect(null).subscribe(topic);

        reserve(data.memberId(), Map.of(1L, 2, 3L, 4));

        StockMessage message = next(messages);
        assertThat(message.changes()).containsExactly(new Change(1, 28), new Change(3, 46));
        assertThat(message.version()).isEqualTo(redisVersion()).isEqualTo(1);
    }

    @Test
    void 취소하면_복구된_잔여가_더_큰_버전으로_온다() throws Exception {
        BlockingQueue<StockMessage> messages = connect(null).subscribe(topic);
        long member = data.memberId();
        long reservationId = reserve(member, Map.of(3L, 3));
        StockMessage reserved = next(messages);

        assertThat(cancel(member, reservationId).getStatusCode()).isEqualTo(HttpStatus.OK);

        StockMessage canceled = next(messages);
        assertThat(canceled.changes()).containsExactly(new Change(3, 50));
        assertThat(canceled.version()).isGreaterThan(reserved.version()).isEqualTo(redisVersion());
        assertThat(redisStock(3)).isEqualTo(50);
    }

    @Test
    void 사장님이_수량을_늘리고_줄이면_Redis_와_같은_잔여가_온다() throws Exception {
        BlockingQueue<StockMessage> messages = connect(null).subscribe(topic);
        reserve(data.memberId(), Map.of(3L, 1));                       // 재고 키 적재 (키가 없으면 수량 저장은 Redis 를 건너뛴다)
        assertThat(next(messages).changes()).containsExactly(new Change(3, 49));

        assertThat(putStock(3, 60, "\"v0\"").getStatusCode()).isEqualTo(HttpStatus.OK);   // +10: 아웃박스 → INCREASE_ONCE
        StockMessage increased = next(messages);
        assertThat(increased.changes()).containsExactly(new Change(3, 59));

        assertThat(putStock(3, 40, "\"v1\"").getStatusCode()).isEqualTo(HttpStatus.OK);   // −20: DECREASE
        StockMessage decreased = next(messages);
        assertThat(decreased.changes()).containsExactly(new Change(3, 39));
        assertThat(decreased.version()).isGreaterThan(increased.version()).isEqualTo(redisVersion()).isEqualTo(3);
        assertThat(redisStock(3)).isEqualTo(39);
    }

    @Test
    void 정합성_보정과_예약_보상도_발행하고_적재는_버전을_올리지_않는다() throws Exception {
        BlockingQueue<StockMessage> messages = connect(null).subscribe(topic);

        stockRedisSync.fillMissing(date, Map.of(3L, 50));               // 적재: 잔여가 바뀐 것이 아니다
        assertThat(redis.opsForValue().get(StockKeys.version(date))).isNull();

        stockRedisSync.adjust(date, 3, -5);                              // N-05·A-12 보정
        assertThat(next(messages).changes()).containsExactly(new Change(3, 45));

        stockStore.release(date, data.memberId(), Map.of(3L, 2), null);  // C-04 DB 실패 보상
        StockMessage released = next(messages);
        assertThat(released.changes()).containsExactly(new Change(3, 47));
        assertThat(released.version()).isEqualTo(2);
        assertThat(messages.poll(500, TimeUnit.MILLISECONDS)).isNull();   // 적재는 메시지가 없다
    }

    @Test
    void 짧은_시간의_여러_변경은_빵별_마지막_값으로_묶여_온다() throws Exception {
        BlockingQueue<StockMessage> messages = connect(null).subscribe(topic);
        stockRedisSync.fillMissing(date, Map.of(1L, 30, 3L, 50));

        for (int i = 0; i < 5; i++) {                                    // Lua 10번: 한 번에 수 ms 라 200ms 안에 끝난다
            stockRedisSync.adjust(date, 1, -1);
            stockRedisSync.adjust(date, 3, -2);
        }

        Map<Long, Integer> latest = new HashMap<>();
        List<StockMessage> received = new ArrayList<>();
        while (latest.getOrDefault(1L, 0) != 25 || latest.getOrDefault(3L, 0) != 40) {
            StockMessage message = next(messages);
            received.add(message);
            message.changes().forEach(change -> latest.put(change.breadId(), change.remaining()));
        }
        assertThat(received.size()).isLessThanOrEqualTo(2);               // 묶음 경계에 걸리면 2번
        assertThat(received.get(received.size() - 1).version()).isEqualTo(10).isEqualTo(redisVersion());
        assertThat(received).extracting(StockMessage::version).isSorted();
        assertThat(received.get(0).changes()).extracting(Change::breadId).doesNotHaveDuplicates();
    }

    @Test
    void 목록의_버전보다_작거나_같은_메시지는_버려도_되고_큰_메시지는_적용하면_Redis_와_같다() throws Exception {
        BlockingQueue<StockMessage> messages = connect(null).subscribe(topic);      // 구독 먼저 (13-3 2단계)
        reserve(data.memberId(), Map.of(3L, 3));
        StockMessage before = next(messages);

        JsonNode snapshot = list();                                       // 그다음 목록(스냅샷)
        long snapshotVersion = snapshot.get("version").asLong();
        assertThat(snapshotVersion).isEqualTo(redisVersion()).isEqualTo(before.version());
        assertThat(remaining(snapshot, 3)).isEqualTo(47);                // 이미 목록에 들어 있는 변경 → 버려도 된다

        reserve(data.memberId(), Map.of(3L, 4));
        StockMessage after = next(messages);
        assertThat(after.version()).isGreaterThan(snapshotVersion);       // 적용할 메시지
        assertThat(after.changes()).containsExactly(new Change(3, redisStock(3)));
        assertThat(redisStock(3)).isEqualTo(43);
    }

    @Test
    void 버전_키는_판매일_다음_날_0시에_재고_키와_함께_만료된다() throws Exception {
        reserve(data.memberId(), Map.of(3L, 1));

        long stockTtl = redis.getExpire(StockKeys.stock(date, 3), TimeUnit.SECONDS);
        long versionTtl = redis.getExpire(StockKeys.version(date), TimeUnit.SECONDS);
        assertThat(versionTtl).isPositive().isCloseTo(stockTtl, org.assertj.core.data.Offset.offset(5L));
    }

    private StompTestClient connect(String token) throws Exception {
        StompTestClient client = new StompTestClient(port, token);
        clients.add(client);
        client.session();
        return client;
    }

    private static StockMessage next(BlockingQueue<StockMessage> messages) throws InterruptedException {
        StockMessage message = messages.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("5초 안에 잔여 메시지가 와야 한다").isNotNull();
        return message;
    }

    private long reserve(long memberId, Map<Long, Integer> quantities) {
        now = now.plusSeconds(1);                                         // 같은 손님 초당 5회 제한
        mutableClock.set(now);
        Map<String, Object> body = new HashMap<>();
        body.put("saleDate", date.toString());
        body.put("pickupSlotId", slot);
        body.put("items", quantities.entrySet().stream()
                .map(e -> Map.of("breadId", e.getKey(), "quantity", e.getValue())).toList());
        HttpHeaders headers = bearer(memberId, Role.CUSTOMER);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/v1/reservations", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("reservationId").asLong();
    }

    private ResponseEntity<JsonNode> cancel(long memberId, long reservationId) {
        HttpHeaders headers = bearer(memberId, Role.CUSTOMER);
        headers.setContentType(MERGE_PATCH);
        return restTemplate.exchange("/api/v1/reservations/" + reservationId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "CANCELED"), headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> putStock(long breadId, int reservable, String ifMatch) {
        HttpHeaders headers = bearer(1L, Role.ADMIN);
        headers.set(HttpHeaders.IF_MATCH, ifMatch);
        Map<String, Object> row = new HashMap<>();
        row.put("breadId", breadId);
        row.put("producedQty", 100);
        row.put("reservableQty", reservable);
        row.put("perUserLimit", null);
        return restTemplate.exchange("/api/v1/admin/sale-dates/" + date + "/stocks", HttpMethod.PUT,
                new HttpEntity<>(List.of(row), headers), JsonNode.class);
    }

    private JsonNode list() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/api/v1/sale-dates/" + date + "/breads",
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static int remaining(JsonNode list, long breadId) {
        for (JsonNode bread : list.get("breads")) {
            if (bread.get("breadId").asLong() == breadId) {
                return bread.get("remaining").asInt();
            }
        }
        throw new AssertionError("목록에 빵이 없음 breadId=" + breadId);
    }

    private HttpHeaders bearer(long memberId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(memberId, role, false));
        return headers;
    }

    private int redisStock(long breadId) {
        return Integer.parseInt(redis.opsForValue().get(StockKeys.stock(date, breadId)));
    }

    private long redisVersion() {
        return Long.parseLong(redis.opsForValue().get(StockKeys.version(date)));
    }
}
