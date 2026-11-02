package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.reservation.Reservation.Cancellation;
import com.fivexbread.reservation.ReservationResponse.ItemResponse;
import com.fivexbread.saledate.DailyStockRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 취소 DB 트랜잭션 (13-2 3·4단계, C-06·A-08). Redis 는 부르지 않는다 (ReservationCancelFacade 가 커밋 후 복구).
 * 도메인(Reservation.cancel)이 판단하고, 조건부 UPDATE 가 확정한다.
 */
@Service
@RequiredArgsConstructor
public class ReservationCancelService {

    private final ReservationRepository reservationRepository;
    private final DailyStockRepository dailyStockRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /** 취소할 예약의 판매일·손님. 커밋 전에 Redis user_qty 세대를 읽어 두려고 먼저 찾는다 */
    record Target(LocalDate saleDate, long memberId) {
    }

    /**
     * @param quantities 되돌린 수량 (breadId 오름차순)
     */
    record Canceled(ReservationResponse response, LocalDate saleDate, long memberId, Map<Long, Integer> quantities) {
    }

    @Transactional(readOnly = true)
    public Target target(long reservationId, ReservationActor actor) {
        Reservation reservation = find(reservationId, actor);
        return new Target(reservation.getSaleDate(), reservation.getMemberId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Canceled cancel(long reservationId, ReservationActor actor, String reason) {
        Reservation reservation = find(reservationId, actor);
        Cancellation cancellation = reservation.cancel(actor, LocalDateTime.now(clock), reason);
        if (reservationRepository.cancelIfReserved(reservationId, cancellation.canceledAt(),
                cancellation.canceledBy(), cancellation.reason()) == 0) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);      // 다른 요청이 먼저 바꿈
        }

        // 예약(C-04)과 같은 breadId 오름차순으로 행을 잠가 순환 대기(데드락)가 없다
        List<ItemResponse> items = jdbcTemplate.query("""
                SELECT i.bread_id, b.name, i.quantity, i.unit_price
                FROM reservation_item i JOIN bread b ON b.id = i.bread_id
                WHERE i.reservation_id = ? ORDER BY i.bread_id""",
                (rs, row) -> new ItemResponse(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)),
                reservationId);
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (ItemResponse item : items) {
            if (dailyStockRepository.decreaseReserved(reservation.getSaleDate(), item.breadId(), item.quantity()) == 0) {
                throw new IllegalStateException("예약 수량보다 많이 되돌리려 함 reservationId=" + reservationId
                        + " breadId=" + item.breadId());
            }
            quantities.put(item.breadId(), item.quantity());
        }

        jdbcTemplate.update("""
                INSERT INTO reservation_history (reservation_id, from_status, to_status, actor_id, changed_at)
                VALUES (?, ?, ?, ?, ?)""",
                reservationId, ReservationStatus.RESERVED.name(), ReservationStatus.CANCELED.name(),
                cancellation.canceledBy(), cancellation.canceledAt());

        ReservationResponse response = ReservationResponse.of(reservation, ReservationStatus.CANCELED,
                cancellation.canceledAt(), items);
        return new Canceled(response, reservation.getSaleDate(), reservation.getMemberId(), quantities);
    }

    // 손님은 본인 예약만. 남의 예약과 없는 예약을 구분하지 않고 404 (13-2 2단계)
    private Reservation find(long reservationId, ReservationActor actor) {
        return (actor.admin()
                ? reservationRepository.findById(reservationId)
                : reservationRepository.findByIdAndMemberId(reservationId, actor.memberId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
