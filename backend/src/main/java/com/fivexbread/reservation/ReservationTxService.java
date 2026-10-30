package com.fivexbread.reservation;

import com.fivexbread.reservation.ReservationResponse.ItemResponse;
import com.fivexbread.saledate.DailyStockRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse create(ReservationPlan plan, String reservationNo, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now(clock);

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
