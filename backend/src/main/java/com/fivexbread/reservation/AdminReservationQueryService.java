package com.fivexbread.reservation;

import com.fivexbread.common.MaskedName;
import com.fivexbread.common.MaskedPhone;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수령용 예약 검색 (A-06, 13-9 2단계). 판매일 + 예약번호 또는 뒷 4자리 중 하나만. 판매일 전체 목록(픽업 준비 목록 A-05)은 2차.
 * - 예약번호: uk_reservation_no (sale_date, reservation_no) → 1행 (EXPLAIN type=const)
 * - 뒷 4자리: ix_resv_phone4 (sale_date, phone_last4) → 그날 같은 뒷 4자리만 (type=ref). 여러 명이면 화면이 이름·픽업 시각으로 고른다
 * 회원은 PK 로 1행씩 붙는다(eq_ref). 항목은 쿼리 한 번으로 모아 붙인다.
 */
@Service
@RequiredArgsConstructor
public class AdminReservationQueryService {

    private static final String SELECT = """
            SELECT r.id, r.reservation_no, r.status, r.sale_date, r.pickup_at, r.total_price, m.name, m.phone
            FROM reservation r JOIN member m ON m.id = r.member_id
            WHERE r.sale_date = :saleDate""";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ReservationItems reservationItems;

    /** @param status null 이면 상태와 상관없이 */
    @Transactional(readOnly = true)
    public List<AdminReservationResponse> search(LocalDate saleDate, String reservationNo, String phoneLast4,
                                                 ReservationStatus status) {
        StringBuilder sql = new StringBuilder(SELECT);
        MapSqlParameterSource params = new MapSqlParameterSource("saleDate", saleDate);
        if (reservationNo != null) {
            sql.append(" AND r.reservation_no = :reservationNo");
            params.addValue("reservationNo", reservationNo);
        } else {
            sql.append(" AND r.phone_last4 = :phoneLast4");
            params.addValue("phoneLast4", phoneLast4);
        }
        if (status != null) {
            sql.append(" AND r.status = :status");
            params.addValue("status", status.name());
        }
        sql.append(" ORDER BY r.pickup_at, r.id");
        return query(sql.toString(), params);
    }

    /** QR 검증 (C-07). 서명으로 확인한 판매일·예약번호의 예약 */
    @Transactional(readOnly = true)
    public Optional<AdminReservationResponse> find(LocalDate saleDate, String reservationNo) {
        return search(saleDate, reservationNo, null, null).stream().findFirst();
    }

    private List<AdminReservationResponse> query(String sql, MapSqlParameterSource params) {
        List<Row> rows = jdbcTemplate.query(sql, params, (rs, i) -> new Row(rs.getLong(1), rs.getString(2),
                ReservationStatus.valueOf(rs.getString(3)), rs.getObject(4, LocalDate.class),
                rs.getObject(5, LocalDateTime.class), rs.getInt(6), rs.getString(7), rs.getString(8)));
        Map<Long, List<ReservationResponse.ItemResponse>> items =
                reservationItems.of(rows.stream().map(Row::id).toList());
        List<AdminReservationResponse> responses = new ArrayList<>();
        for (Row row : rows) {
            responses.add(new AdminReservationResponse(row.id(), row.reservationNo(), row.status(), row.saleDate(),
                    ReservationResponse.toOffset(row.pickupAt()), MaskedName.from(row.name()),
                    row.phone() == null ? null : MaskedPhone.from(row.phone()),
                    items.getOrDefault(row.id(), List.of()), row.totalPrice()));
        }
        return responses;
    }

    private record Row(long id, String reservationNo, ReservationStatus status, LocalDate saleDate,
                       LocalDateTime pickupAt, int totalPrice, String name, String phone) {
    }
}
