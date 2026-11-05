package com.fivexbread.reservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** 손님 본인 예약. PK 로 1행을 찾은 뒤 member_id 를 비교해 추가 인덱스가 필요 없다 (13-2 어필 포인트) */
    Optional<Reservation> findByIdAndMemberId(long id, long memberId);

    /**
     * 취소 확정 (13-2 4단계, 비교 후 교체). 취소·수령·직권 취소가 동시에 와도 RESERVED 인 동안 먼저 실행한 하나만 1행.
     * 0이면 다른 요청이 먼저 상태를 바꾼 것 → 409.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE reservation
            SET status = 'CANCELED', canceled_at = :canceledAt, canceled_by = :canceledBy, cancel_reason = :reason
            WHERE id = :id AND status = 'RESERVED'
            """, nativeQuery = true)
    int cancelIfReserved(@Param("id") long id, @Param("canceledAt") LocalDateTime canceledAt,
                         @Param("canceledBy") long canceledBy, @Param("reason") String reason);

    /**
     * 수령 확정 (13-9 3단계, A-06). RESERVED 이고 판매일이 오늘인 예약만 1행. 손님 취소·직권 취소와 같은 status 조건이라
     * 동시에 와도 먼저 실행한 하나만 바뀐다 (N-07, E-09). 0이면 원인을 Reservation.pickUpRejection 으로 가린다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE reservation SET status = 'PICKED_UP', picked_up_at = :pickedUpAt
            WHERE id = :id AND status = 'RESERVED' AND sale_date = :today
            """, nativeQuery = true)
    int pickUpIfReserved(@Param("id") long id, @Param("pickedUpAt") LocalDateTime pickedUpAt,
                         @Param("today") LocalDate today);

    /**
     * 그날 손님의 예약 건수(취소 제외). Redis user_qty 의 건수 필드를 다시 채울 때의 기준으로만 쓴다 (decisions.md 6장).
     * ix_resv_member_status (member_id, status, sale_date) 커버링.
     */
    @Query(value = """
            SELECT COUNT(*) FROM reservation
            WHERE member_id = :memberId AND sale_date = :saleDate AND status <> 'CANCELED'
            """, nativeQuery = true)
    long countActive(@Param("memberId") long memberId, @Param("saleDate") LocalDate saleDate);

    /** 그날 마지막 예약번호. 예약번호 Redis 카운터를 다시 채울 때의 기준 (uk_reservation_no 인덱스) */
    @Query(value = "SELECT MAX(reservation_no) FROM reservation WHERE sale_date = :saleDate", nativeQuery = true)
    String findLastReservationNo(@Param("saleDate") LocalDate saleDate);
}
