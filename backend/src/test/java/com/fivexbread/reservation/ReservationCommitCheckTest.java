package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.saledate.StockKeys;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예약 DB 실패 뒤 보상 판단 (D2, decisions.md 6장 "예약 DB 실패 뒤 보상 판단").
 * 원인을 모르는 실패면 같은 Idempotency-Key 의 예약이 DB 에 있는지 보고, 있으면 보상하지 않고 그 예약을 201 로 돌려준다.
 * "커밋은 됐지만 응답이 유실됨"은 실제 트랜잭션을 실행한 뒤 예외를 던지는 스파이로 만든다.
 */
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "fivexbread.reservation.mode=redis")
class ReservationCommitCheckTest extends IntegrationTestSupport {

    private static final String URL = "/api/v1/reservations";

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
    PlatformTransactionManager transactionManager;

    @Autowired
    DataSource dataSource;

    @MockitoSpyBean
    ReservationTxService txService;

    @MockitoSpyBean
    ReservationQueryService queryService;

    @MockitoSpyBean
    ReservationNumbers numbers;

    @MockitoSpyBean
    ConnectionPoolStatus poolStatus;

    private SaleDateTestData data;
    private LocalDate date;
    private long slot;
    private long member;

    @BeforeEach
    void setUp() {
        mutableClock.reset();
        data = new SaleDateTestData(jdbcTemplate, LocalDate.now(clock));
        data.clean();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        date = data.today().plusDays(12);
        data.saleDate(date, date.minusDays(3).atTime(10, 0), date.minusDays(1).atTime(20, 0), false);
        slot = data.slot(date, 11);
        data.stock(date, 2, 100, 50, 0, 4);
        data.stock(date, 3, 100, 50, 0, null);
        mutableClock.set(date.minusDays(3).atTime(11, 0));
        member = data.memberId();
    }

    @AfterEach
    void tearDown() {
        reset(txService, queryService, numbers, poolStatus);
        mutableClock.reset();
        data.clean();
    }

    @Test
    void 커밋_뒤_응답이_유실되면_보상하지_않고_DB에서_다시_읽은_예약을_201로_돌려주고_같은_키_재요청도_똑같다() {
        // 스파이는 트랜잭션 프록시 안쪽에서 불린다. 실제 작업을 별도 트랜잭션으로 커밋한 뒤 예외를 던져야 "커밋 후 유실"이 된다
        TransactionTemplate committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        doAnswer(invocation -> {
            committed.execute(status -> {
                try {
                    return invocation.callRealMethod();
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            });
            throw new QueryTimeoutException("커밋 응답 유실");
        }).when(txService).create(any(), anyString(), anyString());
        UUID key = UUID.randomUUID();

        ResponseEntity<JsonNode> first = post(key, Map.of(3L, 2, 2L, 1));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = first.getBody().get("reservationId").asLong();
        assertThat(first.getHeaders().getLocation()).hasToString(URL + "/" + id);
        assertThat(first.getBody().get("status").asText()).isEqualTo("RESERVED");
        assertThat(first.getBody().get("items")).hasSize(2);
        assertThat(first.getBody().get("items").get(0).get("breadId").asLong()).isEqualTo(2);    // breadId 순
        assertThat(first.getBody().get("items").get(0).get("name").asText()).isEqualTo(breadName(2));
        assertThat(first.getBody().get("canceledAt").isNull()).isTrue();

        // 보상하지 않아 Redis = DB
        assertThat(reserved(3)).isEqualTo(2);
        assertThat(redisStock(3)).isEqualTo(50 - 2);
        assertThat(redisStock(2)).isEqualTo(50 - 1);
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "3")).isEqualTo("2");
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "count")).isEqualTo("1");

        // 같은 키 재요청: 저장된 응답이 그대로 (본문·Location 모두 같다)
        reset(txService);
        ResponseEntity<JsonNode> retry = post(key, Map.of(3L, 2, 2L, 1));
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(retry.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(reservationCount()).isEqualTo(1);
    }

    @Test
    void 커밋되지_않은_실패면_보상하고_같은_키로_다시_시도할_수_있다() {
        doThrow(new QueryTimeoutException("커밋 전 실패")).when(txService).create(any(), anyString(), anyString());
        UUID key = UUID.randomUUID();

        ResponseEntity<JsonNode> failed = post(key, Map.of(3L, 2));

        assertThat(failed.getStatusCode().is5xxServerError()).isTrue();
        assertThat(reservationCount()).isZero();
        assertThat(redisStock(3)).isEqualTo(50);
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "3")).isIn("0", null);

        reset(txService);
        assertThat(post(key, Map.of(3L, 2)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(redisStock(3)).isEqualTo(50 - 2);
    }

    @Test
    void 커밋_여부_조회도_실패하면_커밋되지_않은_것으로_보고_보상한다() {
        doThrow(new QueryTimeoutException("커밋 전 실패")).when(txService).create(any(), anyString(), anyString());
        doThrow(new TransientDataAccessResourceException("DB 장애")).when(queryService)
                .findCreated(anyLong(), anyString());

        ResponseEntity<JsonNode> failed = post(UUID.randomUUID(), Map.of(3L, 2));

        assertThat(failed.getStatusCode().is5xxServerError()).isTrue();
        assertThat(reservationCount()).isZero();
        assertThat(redisStock(3)).isEqualTo(50);
    }

    @Test
    void 트랜잭션_안의_업무_판단으로_실패하면_커밋_여부를_조회하지_않고_보상한다() {
        doAnswer(invocation -> {                                    // 규칙 검사·Redis 차감 뒤 탈퇴 → 회원 행을 잠근 뒤 401
            jdbcTemplate.update("UPDATE member SET status = 'WITHDRAWN' WHERE id = ?", member);
            return invocation.callRealMethod();
        }).when(txService).create(any(), anyString(), anyString());

        ResponseEntity<JsonNode> failed = post(UUID.randomUUID(), Map.of(3L, 2));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(queryService, never()).findCreated(anyLong(), anyString());
        assertThat(redisStock(3)).isEqualTo(50);
    }

    @Test
    void 락_실패면_커밋_여부를_조회하지_않고_보상하고_409_LOCK_CONFLICT() {
        doThrow(new CannotAcquireLockException("Lock wait timeout exceeded")).when(txService)
                .create(any(), anyString(), anyString());

        ResponseEntity<JsonNode> failed = post(UUID.randomUUID(), Map.of(3L, 2));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(queryService, never()).findCreated(anyLong(), anyString());
        assertThat(failed.getBody().get("code").asText()).isEqualTo("LOCK_CONFLICT");
        assertThat(redisStock(3)).isEqualTo(50);
    }

    @Test
    void 결과를_모르지만_커넥션_풀_대기가_있으면_커밋_여부를_조회하지_않고_보상한다() {
        doThrow(new QueryTimeoutException("커밋 전 실패")).when(txService).create(any(), anyString(), anyString());
        doReturn(true).when(poolStatus).hasThreadsAwaitingConnection();

        ResponseEntity<JsonNode> failed = post(UUID.randomUUID(), Map.of(3L, 2));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(queryService, never()).findCreated(anyLong(), anyString());
        assertThat(reservationCount()).isZero();
        assertThat(redisStock(3)).isEqualTo(50);
    }

    @Test
    void 커넥션_풀_대기는_Hikari의_대기_스레드_수로_본다() throws Exception {
        // 풀(최대 10개)을 모두 잡은 채 다른 스레드가 커넥션을 기다리게 만든다
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        List<Connection> held = new ArrayList<>();
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try {
            assertThat(poolStatus.hasThreadsAwaitingConnection()).isFalse();
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                held.add(dataSource.getConnection());
            }
            Future<?> waiting = waiter.submit(() -> {
                try (Connection ignored = dataSource.getConnection()) {
                    return null;
                }
            });
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!poolStatus.hasThreadsAwaitingConnection() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(poolStatus.hasThreadsAwaitingConnection()).isTrue();

            held.removeFirst().close();                 // 하나를 돌려주면 기다리던 스레드가 가져간다
            waiting.get(5, TimeUnit.SECONDS);
            assertThat(poolStatus.hasThreadsAwaitingConnection()).isFalse();
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
            waiter.shutdownNow();
        }
    }

    @Test
    void 트랜잭션을_열지_못하면_커밋_여부를_조회하지_않고_보상하고_503() {
        doThrow(new CannotCreateTransactionException("Connection is not available, request timed out after 10000ms"))
                .when(txService).create(any(), anyString(), anyString());

        ResponseEntity<JsonNode> failed = post(UUID.randomUUID(), Map.of(3L, 2));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(queryService, never()).findCreated(anyLong(), anyString());
        assertThat(redisStock(3)).isEqualTo(50);
        assertThat(redis.opsForHash().get(StockKeys.userQty(date, member), "3")).isIn("0", null);
    }

    @Test
    void JDBC_커넥션을_얻지_못하면_커밋_여부를_조회하지_않고_보상하고_503() {
        doThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"))
                .when(txService).create(any(), anyString(), anyString());

        ResponseEntity<JsonNode> failed = post(UUID.randomUUID(), Map.of(3L, 2));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(queryService, never()).findCreated(anyLong(), anyString());
        assertThat(redisStock(3)).isEqualTo(50);
    }

    @Test
    void 예약번호를_얻지_못하면_트랜잭션도_커밋_여부_조회도_없이_보상하고_503() {
        doThrow(new RedisConnectionFailureException("예약번호 INCR 실패")).when(numbers).next(any());

        ResponseEntity<JsonNode> failed = post(UUID.randomUUID(), Map.of(3L, 2));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(txService, never()).create(any(), anyString(), anyString());
        verify(queryService, never()).findCreated(anyLong(), anyString());
        assertThat(reservationCount()).isZero();
        assertThat(redisStock(3)).isEqualTo(50);
    }

    private ResponseEntity<JsonNode> post(UUID key, Map<Long, Integer> quantities) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(member, Role.CUSTOMER, false));
        headers.set("Idempotency-Key", key.toString());
        Map<String, Object> body = Map.of("saleDate", date.toString(), "pickupSlotId", slot,
                "items", quantities.entrySet().stream()
                        .map(e -> Map.of("breadId", e.getKey(), "quantity", e.getValue())).toList());
        return restTemplate.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private int reservationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation WHERE member_id = ? AND sale_date = ?",
                Integer.class, member, date);
    }

    private int reserved(long breadId) {
        return jdbcTemplate.queryForObject("SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = ?",
                Integer.class, date, breadId);
    }

    private String breadName(long breadId) {
        return jdbcTemplate.queryForObject("SELECT name FROM bread WHERE id = ?", String.class, breadId);
    }

    private int redisStock(long breadId) {
        return Integer.parseInt(redis.opsForValue().get(StockKeys.stock(date, breadId)));
    }
}
