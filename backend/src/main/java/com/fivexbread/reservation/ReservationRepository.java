package com.fivexbread.reservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

    /** 노쇼 후보 (A-07). 잠그지 않고 ix_resv_date_status 만 읽는다. 잠그는 것은 아래 lockReserved 가 PK 로 한다 */
    @Query(value = """
            SELECT id FROM reservation
            WHERE sale_date = :saleDate AND status = 'RESERVED'
            ORDER BY id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findReservedIds(@Param("saleDate") LocalDate saleDate, @Param("limit") int limit);

    /**
     * 아직 RESERVED 가 남은 판매일 (A-07 이어받기). 노쇼 처리를 못 한 지난 날을 찾는다.
     * ix_resv_date_status (sale_date, status) 를 기간 범위로 읽는다. 기간을 7일로 묶어 쌓인 이력 전체를 훑지 않는다
     */
    @Query("""
            select distinct r.saleDate from Reservation r
            where r.saleDate between :from and :to and r.status = com.fivexbread.reservation.ReservationStatus.RESERVED
            order by r.saleDate
            """)
    List<LocalDate> findSaleDatesWithReserved(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 후보를 PK 로 잠그고, 아직 RESERVED 인 것만 돌려준다 (A-07). 수령·취소도 PK 부터 잠가 같은 순서라 교착이 없다.
     * (sale_date, status) 인덱스로 바로 잠그면 인덱스 → PK 순서가 되어 수령·취소(PK → 인덱스)와 어긋난다.
     * SKIP LOCKED 는 쓰지 않는다. 건너뛴 행이 수령 롤백으로 RESERVED 로 남으면 후보에서도 빠질 수 있다.
     */
    @Query(value = """
            SELECT id FROM reservation
            WHERE id IN (:ids) AND status = 'RESERVED'
            ORDER BY id FOR UPDATE
            """, nativeQuery = true)
    List<Long> lockReserved(@Param("ids") Collection<Long> ids);

    /** 노쇼 확정 (13-10 2단계). 수령·취소와 같은 status 조건이라 같은 예약은 먼저 실행한 하나만 바뀐다 (N-07) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE reservation SET status = 'NO_SHOW' WHERE id IN (:ids) AND status = 'RESERVED'",
            nativeQuery = true)
    int markNoShow(@Param("ids") Collection<Long> ids);

    /** 그날 노쇼가 있는 회원 (R-17 대상). 이번 실행이 바꾼 행이 아니라 DB 로 구해, 중간에 죽은 뒤 다시 돌려도 빠지는 회원이 없다 */
    @Query(value = """
            SELECT DISTINCT member_id FROM reservation
            WHERE sale_date = :saleDate AND status = 'NO_SHOW'
            ORDER BY member_id
            """, nativeQuery = true)
    List<Long> findNoShowMemberIds(@Param("saleDate") LocalDate saleDate);

    /**
     * 기간 안에 노쇼한 판매일이 threshold 일 이상인 회원 (R-17). 같은 날 여러 건은 1회로 센다.
     * ix_resv_member_status (member_id, status, sale_date) 커버링
     */
    @Query(value = """
            SELECT member_id FROM reservation
            WHERE member_id IN (:memberIds) AND status = 'NO_SHOW' AND sale_date BETWEEN :from AND :to
            GROUP BY member_id HAVING COUNT(DISTINCT sale_date) >= :threshold
            """, nativeQuery = true)
    List<Long> findRepeatNoShowMemberIds(@Param("memberIds") Collection<Long> memberIds, @Param("from") LocalDate from,
                                         @Param("to") LocalDate to, @Param("threshold") int threshold);

    /**
     * 그날 손님의 예약 건수(취소 제외). Redis user_qty 의 건수 필드를 다시 채울 때의 기준으로만 쓴다 (decisions.md 6장).
     * ix_resv_member_status (member_id, status, sale_date) 커버링.
     */
    @Query(value = """
            SELECT COUNT(*) FROM reservation
            WHERE member_id = :memberId AND sale_date = :saleDate AND status <> 'CANCELED'
            """, nativeQuery = true)
    long countActive(@Param("memberId") long memberId, @Param("saleDate") LocalDate saleDate);

    /** 예약들의 항목을 한 번에 읽는다 (C-05). uk_item (reservation_id, bread_id) 순서 그대로라 정렬이 따로 없다 */
    @Query(value = """
            SELECT i.reservation_id AS reservationId, i.bread_id AS breadId, b.name AS name,
                   i.quantity AS quantity, i.unit_price AS unitPrice
            FROM reservation_item i JOIN bread b ON b.id = i.bread_id
            WHERE i.reservation_id IN (:reservationIds)
            ORDER BY i.reservation_id, i.bread_id
            """, nativeQuery = true)
    List<ReservationItemRow> findItems(@Param("reservationIds") Collection<Long> reservationIds);

    /** 그날 마지막 예약번호. 예약번호 Redis 카운터를 다시 채울 때의 기준 (uk_reservation_no 인덱스) */
    @Query(value = "SELECT MAX(reservation_no) FROM reservation WHERE sale_date = :saleDate", nativeQuery = true)
    String findLastReservationNo(@Param("saleDate") LocalDate saleDate);
}
