package com.fivexbread.domain.reservation;

import com.fivexbread.domain.reservation.model.Reservation;
import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import com.fivexbread.domain.reservation.model.vo.AdminReservationCursor;
import com.fivexbread.inbound.controller.reservation.model.response.AdminReservationDetailResponse;
import com.fivexbread.inbound.controller.reservation.model.response.AdminReservationListResponse;
import com.fivexbread.inbound.controller.reservation.model.response.AdminReservationResponse;
import com.fivexbread.inbound.controller.reservation.model.response.ReservationResponse;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.common.MaskedName;
import com.fivexbread.common.MaskedPhone;
import com.fivexbread.domain.member.model.enums.MemberStatus;
import com.fivexbread.domain.member.model.enums.Role;
import com.fivexbread.inbound.controller.reservation.model.response.AdminReservationDetailResponse.ActorType;
import com.fivexbread.inbound.controller.reservation.model.response.AdminReservationDetailResponse.HistoryResponse;
import com.fivexbread.inbound.controller.reservation.model.response.AdminReservationDetailResponse.MemberSummary;
import com.fivexbread.inbound.controller.reservation.model.response.ReservationResponse.ItemResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
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
 * 사장님 예약 조회. 회원은 PK 로 1행씩 붙고(eq_ref), 항목은 쿼리 한 번으로 모아 붙인다(ReservationItems).
 * - 수령 검색 (A-06, 13-9 2단계): 판매일 + 예약번호(uk_reservation_no, const) 또는 뒷 4자리(ix_resv_phone4, ref) 중 하나
 * - 판매일 예약 목록 (A-05): ix_resv_date_pickup / ix_resv_date_status_pickup 순서 그대로 커서로 끊어 읽는다.
 *   쿼리는 본체 1번 + 항목 IN 1번으로 고정 (decisions.md 6장 "A-05 판매일 예약 목록")
 * - 예약 상세: 본체·회원·항목·이력
 * 손님 이름·번호는 마스킹한 값만 내보낸다 (AU-10). 탈퇴한 손님이면 둘 다 null.
 */
@Service
@RequiredArgsConstructor
public class AdminReservationQueryService {

    private static final String CARD_COLUMNS = """
            SELECT r.id, r.reservation_no, r.status, r.sale_date, r.pickup_slot_id, r.pickup_at, r.total_price,
                   m.name, m.phone, m.status""";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ReservationItems reservationItems;
    private final Clock clock;

    /** @param status null 이면 상태와 상관없이 */
    @Transactional(readOnly = true)
    public List<AdminReservationResponse> search(LocalDate saleDate, String reservationNo, String phoneLast4,
                                                 ReservationStatus status) {
        StringBuilder sql = new StringBuilder(CARD_COLUMNS).append("""

                FROM reservation r JOIN member m ON m.id = r.member_id
                WHERE r.sale_date = :saleDate""");
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
        return cards(jdbcTemplate.query(sql.toString(), params, AdminReservationQueryService::card));
    }

    /** QR 검증 (C-07). 서명으로 확인한 판매일·예약번호의 예약 */
    @Transactional(readOnly = true)
    public Optional<AdminReservationResponse> find(LocalDate saleDate, String reservationNo) {
        return search(saleDate, reservationNo, null, null).stream().findFirst();
    }

    /**
     * 판매일 예약 목록 (A-05). 픽업 시각 → 예약번호 순, size + 1 개를 읽어 다음 페이지가 있는지 판단한다.
     * 없는 판매일·그날이 아닌 시간대는 404 가 아니라 빈 목록이다(존재 확인 쿼리를 더하지 않는다).
     *
     * @param status       null 이면 상태와 상관없이
     * @param pickupSlotId null 이면 모든 시간대
     * @param cursor       첫 페이지면 null
     */
    @Transactional(readOnly = true)
    public AdminReservationListResponse list(LocalDate saleDate, ReservationStatus status, Long pickupSlotId,
                                             AdminReservationCursor cursor, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource("saleDate", saleDate).addValue("limit", size + 1);
        if (status != null) {
            params.addValue("status", status.name());
        }
        if (pickupSlotId != null) {
            params.addValue("pickupSlotId", pickupSlotId);
        }
        if (cursor != null) {
            params.addValue("cursorPickupAt", cursor.pickupAt()).addValue("cursorNo", cursor.reservationNo());
        }
        List<CardRow> rows = jdbcTemplate.query(listSql(status != null, pickupSlotId != null, cursor != null), params,
                AdminReservationQueryService::card);
        boolean hasNext = rows.size() > size;
        List<CardRow> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext
                ? new AdminReservationCursor(page.getLast().pickupAt(), page.getLast().reservationNo()).encode()
                : null;
        return new AdminReservationListResponse(cards(page), nextCursor, hasNext);
    }

    /**
     * 판매일 예약 목록 본체. 인덱스 힌트 없이 옵티마이저가 고르는 인덱스를 EXPLAIN 테스트로 지킨다:
     * 상태가 없으면 ix_resv_date_pickup (sale_date, pickup_at, reservation_no),
     * 있으면 ix_resv_date_status_pickup (sale_date, status, pickup_at, reservation_no). 둘 다 ORDER BY 와 순서가 같아 정렬이 없다.
     * 시간대는 JOIN 이 아니라 한 번만 실행되는 스칼라 서브쿼리로 픽업 시각을 꺼내 인덱스의 pickup_at 자리에 넣는다.
     * pickup_slot 을 상수 테이블로 JOIN 하면 인덱스는 같아도 MySQL 이 ORDER BY 의 pickup_at 을 상수로 보지 못해 예약번호로 다시 정렬했다.
     * 시간대의 시각은 바뀌지 않아(decisions.md 6장 "A-04 픽업 시간대·휴무일") 픽업 시각이 같으면 같은 시간대다.
     * 그날의 시간대가 아니면 서브쿼리가 NULL 이라 결과가 비어 있다.
     * 커서 조건은 C-05 와 같은 이유로 행 생성자가 아니라 OR 로 푼다.
     */
    static String listSql(boolean status, boolean pickupSlot, boolean cursor) {
        return CARD_COLUMNS + """

                FROM reservation r
                JOIN member m ON m.id = r.member_id
                WHERE r.sale_date = :saleDate"""
                + (status ? " AND r.status = :status" : "")
                + (pickupSlot ? "\n  AND r.pickup_at = (SELECT s.pickup_at FROM pickup_slot s"
                                + " WHERE s.id = :pickupSlotId AND s.sale_date = :saleDate)" : "")
                + (cursor ? "\n  AND (r.pickup_at > :cursorPickupAt"
                            + " OR (r.pickup_at = :cursorPickupAt AND r.reservation_no > :cursorNo))" : "")
                + "\nORDER BY r.pickup_at, r.reservation_no LIMIT :limit";
    }

    /** 사장님 예약 상세. 없으면 404 */
    @Transactional(readOnly = true)
    public AdminReservationDetailResponse detail(long reservationId) {
        Map<String, Object> id = Map.of("id", reservationId);
        DetailRow row = jdbcTemplate.query("""
                SELECT r.id, r.reservation_no, r.status, r.sale_date, r.pickup_slot_id, r.pickup_at, r.total_price,
                       r.canceled_at, r.cancel_reason, r.picked_up_at, r.created_at, m.id, m.name, m.phone, m.status
                FROM reservation r JOIN member m ON m.id = r.member_id
                WHERE r.id = :id""", id, rs -> rs.next() ? detailRow(rs) : null);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        List<ItemResponse> items = reservationItems.of(reservationId);
        // actor_id 가 없으면 시스템(노쇼 배치). 사장님 id·이름은 내보내지 않고 역할만
        List<HistoryResponse> history = jdbcTemplate.query("""
                SELECT h.from_status, h.to_status, h.actor_id, a.role, h.changed_at
                FROM reservation_history h LEFT JOIN member a ON a.id = h.actor_id
                WHERE h.reservation_id = :id ORDER BY h.changed_at, h.id""", id, (rs, i) -> new HistoryResponse(
                status(rs.getString(1)), status(rs.getString(2)), actorType(rs.getObject(3), rs.getString(4)),
                ReservationResponse.toOffset(rs.getObject(5, LocalDateTime.class))));

        LocalDateTime cancelDeadline = Reservation.cancelDeadline(row.pickupAt());
        boolean cancelable = Reservation.cancelable(row.status(), row.pickupAt(), LocalDateTime.now(clock));
        MemberSummary member = new MemberSummary(row.memberId(), maskedName(row.name(), row.memberStatus()),
                maskedPhone(row.phone()), row.memberStatus());
        return new AdminReservationDetailResponse(row.id(), row.reservationNo(), row.status(), row.saleDate(),
                row.pickupSlotId(), ReservationResponse.toOffset(row.pickupAt()),
                ReservationResponse.toOffset(cancelDeadline), cancelable,
                ReservationResponse.toOffset(row.canceledAt()), row.cancelReason(),
                ReservationResponse.toOffset(row.pickedUpAt()), ReservationResponse.toOffset(row.createdAt()),
                items, row.totalPrice(), member, history);
    }

    private List<AdminReservationResponse> cards(List<CardRow> rows) {
        Map<Long, List<ItemResponse>> items = reservationItems.of(rows.stream().map(CardRow::id).toList());
        List<AdminReservationResponse> responses = new ArrayList<>();
        for (CardRow row : rows) {
            responses.add(new AdminReservationResponse(row.id(), row.reservationNo(), row.status(), row.saleDate(),
                    row.pickupSlotId(), ReservationResponse.toOffset(row.pickupAt()),
                    maskedName(row.name(), row.memberStatus()), maskedPhone(row.phone()),
                    items.getOrDefault(row.id(), List.of()), row.totalPrice()));
        }
        return responses;
    }

    // 탈퇴 회원의 이름은 "탈퇴회원"으로 바뀌어 있다(AU-07). 마스킹하면 "탈**원"이 되므로 번호처럼 null 로 내보낸다
    private static MaskedName maskedName(String name, MemberStatus memberStatus) {
        return memberStatus == MemberStatus.WITHDRAWN ? null : MaskedName.from(name);
    }

    private static MaskedPhone maskedPhone(String phone) {
        return phone == null ? null : MaskedPhone.from(phone);
    }

    private static ActorType actorType(Object actorId, String role) {
        if (actorId == null) {
            return ActorType.SYSTEM;
        }
        return Role.ADMIN.name().equals(role) ? ActorType.ADMIN : ActorType.CUSTOMER;
    }

    private static ReservationStatus status(String value) {
        return value == null ? null : ReservationStatus.valueOf(value);
    }

    private static CardRow card(ResultSet rs, int rowNum) throws SQLException {
        return new CardRow(rs.getLong(1), rs.getString(2), ReservationStatus.valueOf(rs.getString(3)),
                rs.getObject(4, LocalDate.class), rs.getLong(5), rs.getObject(6, LocalDateTime.class), rs.getInt(7),
                rs.getString(8), rs.getString(9), MemberStatus.valueOf(rs.getString(10)));
    }

    private static DetailRow detailRow(ResultSet rs) throws SQLException {
        return new DetailRow(rs.getLong(1), rs.getString(2), ReservationStatus.valueOf(rs.getString(3)),
                rs.getObject(4, LocalDate.class), rs.getLong(5), rs.getObject(6, LocalDateTime.class), rs.getInt(7),
                rs.getObject(8, LocalDateTime.class), rs.getString(9), rs.getObject(10, LocalDateTime.class),
                rs.getObject(11, LocalDateTime.class), rs.getLong(12), rs.getString(13), rs.getString(14),
                MemberStatus.valueOf(rs.getString(15)));
    }

    private record CardRow(long id, String reservationNo, ReservationStatus status, LocalDate saleDate,
                           long pickupSlotId, LocalDateTime pickupAt, int totalPrice, String name, String phone,
                           MemberStatus memberStatus) {
    }

    private record DetailRow(long id, String reservationNo, ReservationStatus status, LocalDate saleDate,
                             long pickupSlotId, LocalDateTime pickupAt, int totalPrice, LocalDateTime canceledAt,
                             String cancelReason, LocalDateTime pickedUpAt, LocalDateTime createdAt, long memberId,
                             String name, String phone, MemberStatus memberStatus) {
    }
}
