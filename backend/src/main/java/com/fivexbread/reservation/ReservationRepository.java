package com.fivexbread.reservation;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

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
