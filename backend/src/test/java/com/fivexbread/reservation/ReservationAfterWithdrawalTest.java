package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.member.WithdrawalService;
import com.fivexbread.member.WithdrawnPhoneHasher;
import com.fivexbread.saledate.SaleDateTestData;
import com.fivexbread.saledate.StockKeys;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * 업무 규칙 검사(잠그지 않고 회원을 읽음)를 통과한 직후 탈퇴가 커밋되는 순간을 스파이로 만든다 (decisions.md 6장 "AU-07 탈퇴와 예약 경쟁").
 * 예약 트랜잭션이 회원 행을 잠근 뒤 WITHDRAWN 을 보고 401 로 끝나야 하고, v2 는 Redis 차감을 되돌려야 한다.
 * 이 잠금이 없으면(v2 의 1차 구현) 탈퇴한 회원에게 RESERVED 예약이 생긴다.
 */
@Import(MutableClockConfig.class)
class ReservationAfterWithdrawalTest extends IntegrationTestSupport {

    @MockitoSpyBean
    ReservationRuleService ruleService;

    @MockitoSpyBean
    ReservationProperties properties;

    @Autowired
    WithdrawalService withdrawalService;

    @Autowired
    WithdrawnPhoneHasher hasher;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    Clock clock;

    @Autowired
    MutableClock mutableClock;

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
        LocalDateTime openAt = date.minusDays(3).atTime(10, 0);
        data.saleDate(date, openAt, date.minusDays(1).atTime(20, 0), false);
        slot = data.slot(date, 11);
        data.stock(date, 3, 100, 50, 0, null);
        mutableClock.set(openAt.plusHours(1));
        member = data.memberId();
    }

    @AfterEach
    void tearDown() {
        mutableClock.reset();
        data.clean();
        jdbcTemplate.update("DELETE FROM member WHERE id = ?", member);           // 탈퇴로 번호가 지워져 clean 이 못 찾는다
    }

    @ParameterizedTest
    @EnumSource(ReservationMode.class)
    void 규칙_검사_뒤_탈퇴가_커밋되면_예약은_401이고_예약도_재고_변화도_없다(ReservationMode mode) {
        doReturn(mode).when(properties).mode();
        doAnswer(invocation -> {
            ReservationPlan plan = (ReservationPlan) invocation.callRealMethod();   // 이 시점에는 ACTIVE 로 읽힘
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT phone, password_hash FROM member WHERE id = ?",
                    member);
            // 다른 요청이 커밋하는 것처럼 다른 스레드에서. 규칙 검사의 읽기 트랜잭션에 섞이지 않게
            CompletableFuture.runAsync(() -> withdrawalService.withdraw(member, (String) row.get("password_hash"),
                    hasher.hash((String) row.get("phone")))).join();
            return plan;
        }).when(ruleService).check(anyLong(), any());

        ResponseEntity<JsonNode> response = reserve();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM member WHERE id = ?", String.class, member))
                .isEqualTo("WITHDRAWN");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservation WHERE member_id = ?", Integer.class,
                member)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT reserved_qty FROM daily_stock WHERE sale_date = ? AND bread_id = 3",
                Integer.class, date)).isZero();
        if (mode == ReservationMode.REDIS) {
            assertThat(redis.opsForValue().get(StockKeys.stock(date, 3))).isEqualTo("50");   // 차감 뒤 보상
        }
    }

    private ResponseEntity<JsonNode> reserve() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.issue(member, Role.CUSTOMER, false));
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("saleDate", date.toString(), "pickupSlotId", slot,
                "items", List.of(Map.of("breadId", 3, "quantity", 1)));
        return restTemplate.exchange("/api/v1/reservations", HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class);
    }
}
