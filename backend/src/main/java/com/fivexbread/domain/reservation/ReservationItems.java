package com.fivexbread.domain.reservation;

import com.fivexbread.domain.reservation.model.vo.ReservationLine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 예약 항목 + 빵 이름 조회. 취소·수령 응답과 수령 검색 결과에 쓴다.
 * breadId 오름차순: 취소가 이 순서로 daily_stock 행을 잠가 예약(C-04)과 잠금 순서가 같다.
 */
@Component
@RequiredArgsConstructor
class ReservationItems {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    List<ReservationLine> of(long reservationId) {
        return of(List.of(reservationId)).getOrDefault(reservationId, List.of());
    }

    /** 예약 여러 건을 쿼리 한 번으로 (N+1 없음). reservationId → 항목 */
    Map<Long, List<ReservationLine>> of(Collection<Long> reservationIds) {
        Map<Long, List<ReservationLine>> items = new LinkedHashMap<>();
        if (reservationIds.isEmpty()) {
            return items;
        }
        jdbcTemplate.query("""
                SELECT i.reservation_id, i.bread_id, b.name, i.quantity, i.unit_price
                FROM reservation_item i JOIN bread b ON b.id = i.bread_id
                WHERE i.reservation_id IN (:ids) ORDER BY i.reservation_id, i.bread_id""",
                Map.of("ids", reservationIds),
                rs -> {
                    items.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
                            .add(new ReservationLine(rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5)));
                });
        return items;
    }
}
