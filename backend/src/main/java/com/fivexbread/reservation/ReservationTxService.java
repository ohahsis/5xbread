package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.member.MemberStatus;
import com.fivexbread.reservation.ReservationResponse.ItemResponse;
import com.fivexbread.saledate.BreadQuantity;
import com.fivexbread.saledate.DailyStockRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 DB 트랜잭션 (13-1 6단계). Redis 는 부르지 않는다 (Facade 가 앞뒤에서 처리).
 * READ COMMITTED: 조건부 UPDATE 는 어차피 최신 커밋 값을 읽고(현재 읽기), 갭 락이 줄어든다.
 */
@Service
@RequiredArgsConstructor
public class ReservationTxService {

    private final DailyStockRepository dailyStockRepository;
    private final ReservationRepository reservationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ReservationProperties properties;
    private final Clock clock;

    /**
     * redis(v2): 검사·차감은 Redis Lua 가 이미 했다. 조건부 UPDATE 는 최종 방어선.
     * 먼저 회원 행을 잠그고 활성인지 본다. 탈퇴(AU-07)도 같은 행을 잠근 뒤 RESERVED 를 세므로, 둘이 겹쳐도
     * "탈퇴했는데 RESERVED 예약이 남는" 경우가 없다 (decisions.md 6장 "AU-07 탈퇴와 예약 경쟁").
     * 회원별 잠금이라 다른 손님끼리는 막지 않고, 인기 빵의 재고 행 경쟁과는 무관하다. 같은 손님의 동시 요청만 줄을 선다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse create(ReservationPlan plan, String reservationNo, String idempotencyKey) {
        lockActiveMember(plan.memberId());
        // breadId 오름차순 (plan 이 정렬해 둠). 모든 예약이 같은 순서로 행을 잠가 순환 대기(데드락)가 없다
        List<Long> shortages = new ArrayList<>();
        for (ReservationPlan.Item item : plan.items()) {
            if (dailyStockRepository.increaseReserved(plan.saleDate(), item.breadId(), item.quantity()) == 0) {
                shortages.add(item.breadId());
            }
        }
        if (!shortages.isEmpty()) {
            throw new StockShortageException(shortages);
        }
        return save(plan, reservationNo, idempotencyKey);
    }

    /**
     * db-only(v1): 재고·1인 한도·하루 건수 검사를 이 트랜잭션 안에서 DB 로 한다 (decisions.md 6장 "예약 측정 모드").
     * 1. 회원 행을 FOR UPDATE 로 잠가 같은 손님의 동시 요청(두 탭, 하루 건수 경쟁)을 줄 세운다. 잠그지 않으면 합계를 읽고
     *    저장하는 사이에 두 요청이 함께 통과한다. 다른 손님끼리는 서로 막지 않는다. 탈퇴와의 경쟁도 v2 와 같이 막는다
     * 2. 하루 건수 422 → 빵별 재고·1인 한도 409. Redis Lua 와 같은 순서, 같은 errors[](재고 부족이 하나라도 있으면 OUT_OF_STOCK)
     * 3. 재고는 v2 와 같은 조건부 UPDATE(breadId 오름차순). 모든 요청이 회원 → 재고 행 순서로 잠가 순환 대기가 없다
     * 실패는 예외로 끝나 트랜잭션 전체가 롤백된다. 되돌릴 Redis 차감이 없어 보상도 없다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse createDbOnly(ReservationPlan plan, String reservationNo, String idempotencyKey) {
        lockActiveMember(plan.memberId());
        if (ReservationLimits.dailyCountExceeded(reservationRepository.countActive(plan.memberId(), plan.saleDate()),
                properties.dailyLimitPerMember())) {
            throw new BusinessException(ErrorCode.RESERVATION_COUNT_EXCEEDED);
        }
        Map<Long, Integer> mine = dailyStockRepository.sumReservedQuantities(plan.memberId(), plan.saleDate()).stream()
                .collect(Collectors.toMap(BreadQuantity::getBreadId, row -> row.getQuantity().intValue()));

        List<StockFailure> failures = new ArrayList<>();
        for (ReservationPlan.Item item : plan.items()) {
            if (dailyStockRepository.increaseReserved(plan.saleDate(), item.breadId(), item.quantity()) == 0) {
                failures.add(new StockFailure(item.breadId(), "OUT_OF_STOCK", remaining(plan, item.breadId())));
            }
            StockFailure overLimit = ReservationLimits.perUserFailure(item, mine.getOrDefault(item.breadId(), 0));
            if (overLimit != null) {
                failures.add(overLimit);
            }
        }
        if (!failures.isEmpty()) {
            throw ReservationStockStore.outOfStock(failures);   // 앞에서 성공한 UPDATE 도 함께 롤백
        }
        return save(plan, reservationNo, idempotencyKey);
    }

    /**
     * 회원 행을 잠그고, 잠근 뒤의 상태가 ACTIVE 가 아니면(그사이 탈퇴) 401. 업무 규칙 검사(ReservationRuleService)는 잠그지 않고 읽어
     * 탈퇴가 그 뒤에 커밋될 수 있다. 예외로 트랜잭션이 롤백되고 v2 는 Facade 가 Redis 차감을 되돌린다.
     */
    private void lockActiveMember(long memberId) {
        String status = jdbcTemplate.queryForObject("SELECT status FROM member WHERE id = ? FOR UPDATE", String.class,
                memberId);
        if (!MemberStatus.ACTIVE.name().equals(status)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }

    private int remaining(ReservationPlan plan, long breadId) {
        return dailyStockRepository.findRemaining(plan.saleDate(), List.of(breadId)).stream()
                .mapToInt(row -> Math.max(row.getQuantity().intValue(), 0)).findFirst().orElse(0);
    }

    private ReservationResponse save(ReservationPlan plan, String reservationNo, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now(clock);
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(reservationNo,
                plan.memberId(), plan.phoneLast4(), plan.saleDate(), plan.pickupSlotId(), plan.pickupAt(),
                plan.totalPrice(), idempotencyKey, now));
        jdbcTemplate.batchUpdate(
                "INSERT INTO reservation_item (reservation_id, bread_id, quantity, unit_price) VALUES (?, ?, ?, ?)",
                plan.items(), plan.items().size(), (ps, item) -> {
                    ps.setLong(1, reservation.getId());
                    ps.setLong(2, item.breadId());
                    ps.setInt(3, item.quantity());
                    ps.setInt(4, item.unitPrice());
                });
        jdbcTemplate.update("""
                INSERT INTO reservation_history (reservation_id, from_status, to_status, actor_id, changed_at)
                VALUES (?, NULL, ?, ?, ?)""",
                reservation.getId(), ReservationStatus.RESERVED.name(), plan.memberId(), now);

        return ReservationResponse.of(reservation, plan.items().stream()
                .map(item -> new ItemResponse(item.breadId(), item.name(), item.quantity(), item.unitPrice()))
                .toList());
    }
}
