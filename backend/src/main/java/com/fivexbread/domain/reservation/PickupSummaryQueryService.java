package com.fivexbread.domain.reservation;

import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import com.fivexbread.domain.reservation.model.vo.PickupSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 픽업 준비 합계 (A-05). 쿼리 2번: 시간대·빵별 수량 합(GROUP BY) + 시간대별 예약 수.
 * 둘 다 그날·그 상태의 예약만 ix_resv_date_status_pickup 으로 읽는다. 예약 수는 픽업 시각으로 묶어 인덱스만 읽는다(커버링, 정렬·임시 테이블 없음).
 * 판매일 안에서 픽업 시각과 시간대는 1:1 이다(uk_pickup_slot). 전체 합계(totals)는 DB 를 다시 읽지 않고 시간대 합을 더한다.
 * 같은 트랜잭션(REPEATABLE READ 의 한 스냅샷)에서 읽어 두 쿼리 사이에 예약이 바뀌어도 수량과 예약 수가 어긋나지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PickupSummaryQueryService {

    static final String QUANTITY_SQL = """
            SELECT r.pickup_slot_id, r.pickup_at, i.bread_id, b.name, b.display_order, SUM(i.quantity)
            FROM reservation r
            JOIN reservation_item i ON i.reservation_id = r.id
            JOIN bread b ON b.id = i.bread_id
            WHERE r.sale_date = :saleDate AND r.status = :status
            GROUP BY r.pickup_slot_id, r.pickup_at, i.bread_id, b.name, b.display_order""";

    static final String COUNT_SQL = """
            SELECT pickup_at, COUNT(*) FROM reservation
            WHERE sale_date = :saleDate AND status = :status
            GROUP BY pickup_at""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public PickupSummary summary(LocalDate saleDate, ReservationStatus status) {
        MapSqlParameterSource params = new MapSqlParameterSource("saleDate", saleDate).addValue("status", status.name());
        List<Row> rows = jdbcTemplate.query(QUANTITY_SQL, params, (rs, i) -> new Row(rs.getLong(1),
                rs.getObject(2, LocalDateTime.class), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getInt(6)));
        Map<LocalDateTime, Integer> counts = new HashMap<>();
        jdbcTemplate.query(COUNT_SQL, params, rs -> {
            counts.put(rs.getObject(1, LocalDateTime.class), rs.getInt(2));
        });

        Comparator<Row> breadOrder = Comparator.comparingInt(Row::displayOrder).thenComparingLong(Row::breadId);
        Map<Long, List<Row>> bySlot = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing(Row::pickupAt).thenComparing(breadOrder))
                .forEach(row -> bySlot.computeIfAbsent(row.slotId(), id -> new ArrayList<>()).add(row));

        List<PickupSummary.Slot> slots = bySlot.entrySet().stream()
                .map(entry -> new PickupSummary.Slot(entry.getKey(),
                        entry.getValue().getFirst().pickupAt(),
                        counts.getOrDefault(entry.getValue().getFirst().pickupAt(), 0),
                        entry.getValue().stream().map(Row::toBread).toList()))
                .toList();

        Map<Long, Row> totals = new LinkedHashMap<>();
        rows.stream().sorted(breadOrder).forEach(row -> totals.merge(row.breadId(), row,
                (sum, next) -> sum.plus(next.quantity())));
        return new PickupSummary(saleDate, status, slots,
                totals.values().stream().map(Row::toBread).toList());
    }

    private record Row(long slotId, LocalDateTime pickupAt, long breadId, String name, int displayOrder, int quantity) {

        Row plus(int more) {
            return new Row(slotId, pickupAt, breadId, name, displayOrder, quantity + more);
        }

        PickupSummary.Bread toBread() {
            return new PickupSummary.Bread(breadId, name, quantity);
        }
    }
}
