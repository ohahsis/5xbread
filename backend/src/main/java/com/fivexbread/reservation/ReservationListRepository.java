package com.fivexbread.reservation;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 내 예약 목록 본체 (13-7). (sale_date, id) 내림차순 커서.
 * 조건은 행 생성자 (sale_date, id) < (?, ?) 가 아니라 OR 로 푼다. 행 생성자는 범위 검색이 안 돼 뒤 페이지일수록 앞의 행을 다 읽는다.
 * 인덱스는 FORCE INDEX 로 고정한다. 커서가 앞쪽이면 남은 행이 많아 옵티마이저가 ix_resv_member_status + filesort 를 골라
 * 회원의 예약을 전부 읽는다 (decisions.md 6장 "C-05 커서 조건").
 * status 는 ix_resv_member_status 의 끝에 붙는 PK 까지 (sale_date, id) 순서가 맞아 역방향으로 읽으면 정렬이 따로 없다.
 */
@Repository
class ReservationListRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** @param cursor 첫 페이지면 null */
    @SuppressWarnings("unchecked")
    List<Reservation> find(long memberId, ReservationStatus status, ReservationCursor cursor, int limit) {
        Query query = entityManager.createNativeQuery(sql(status != null, cursor != null), Reservation.class)
                .setParameter("memberId", memberId)
                .setParameter("limit", limit);
        if (status != null) {
            query.setParameter("status", status.name());
        }
        if (cursor != null) {
            query.setParameter("saleDate", cursor.saleDate()).setParameter("id", cursor.id());
        }
        return query.getResultList();
    }

    static String sql(boolean status, boolean cursor) {
        return "SELECT * FROM reservation FORCE INDEX (" + (status ? "ix_resv_member_status" : "ix_resv_member_list") + ")"
                + " WHERE member_id = :memberId"
                + (status ? " AND status = :status" : "")
                + (cursor ? " AND (sale_date < :saleDate OR (sale_date = :saleDate AND id < :id))" : "")
                + " ORDER BY sale_date DESC, id DESC LIMIT :limit";
    }
}
