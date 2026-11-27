package com.fivexbread.domain.reservation.model.vo;

import com.fivexbread.common.MaskedName;
import com.fivexbread.common.MaskedPhone;
import com.fivexbread.domain.member.model.enums.MemberStatus;
import com.fivexbread.domain.reservation.model.enums.HistoryActorType;
import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 사장님 예약 상세. 손님 상세의 내용 + 취소 사유·수령 시각·접수 시각 + 회원(마스킹) + 상태 이력 */
public record AdminReservationDetail(long reservationId, String reservationNo, ReservationStatus status,
                                     LocalDate saleDate, long pickupSlotId, LocalDateTime pickupAt,
                                     LocalDateTime cancelDeadline, boolean cancelable, LocalDateTime canceledAt,
                                     String cancelReason, LocalDateTime pickedUpAt, LocalDateTime createdAt,
                                     List<ReservationLine> items, int totalPrice, MemberSummary member,
                                     List<History> history) {

    /** 이름·번호는 마스킹한 값 (AU-10). 탈퇴한 회원이면 둘 다 null */
    public record MemberSummary(long memberId, MaskedName name, MaskedPhone phone, MemberStatus status) {
    }

    /** reservation_history 한 줄. 시간순 */
    public record History(ReservationStatus fromStatus, ReservationStatus toStatus, HistoryActorType actorType,
                          LocalDateTime changedAt) {
    }
}
