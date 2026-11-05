package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수령 처리 (A-06, 13-9 3단계). 재고는 그대로라 Redis 를 부르지 않고, DB 트랜잭션 하나로 끝난다.
 * 조건부 UPDATE 를 먼저 실행해 확정하고, 그다음 읽는다. 0행이면 읽은 상태로 원인(404·409·422)을 가리고, 1행이면 응답을 만든다.
 * 먼저 읽고 판단하면 읽기와 UPDATE 사이에 취소가 끼어들 수 있어, 판단은 어차피 UPDATE 조건이 다시 해야 한다.
 */
@Service
@RequiredArgsConstructor
public class ReservationPickupService {

    private final ReservationRepository reservationRepository;
    private final ReservationItems reservationItems;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse pickUp(long reservationId, long adminId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        int updated = reservationRepository.pickUpIfReserved(reservationId, now, today);
        // clearAutomatically 라 아래 조회는 UPDATE 이후의 최신 값 (READ COMMITTED: 다른 트랜잭션이 먼저 커밋한 상태도 보임)
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (updated == 0) {
            BusinessException rejection = reservation.pickUpRejection(today);
            if (rejection == null) {
                throw new IllegalStateException("수령 조건을 만족하는데 0행 reservationId=" + reservationId);
            }
            throw rejection;
        }

        jdbcTemplate.update("""
                INSERT INTO reservation_history (reservation_id, from_status, to_status, actor_id, changed_at)
                VALUES (?, ?, ?, ?, ?)""",
                reservationId, ReservationStatus.RESERVED.name(), ReservationStatus.PICKED_UP.name(), adminId, now);
        return ReservationResponse.pickedUp(reservation, now, reservationItems.of(reservationId));
    }
}
