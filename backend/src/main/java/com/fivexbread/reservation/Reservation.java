package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * V1 reservation 테이블 (C-04, C-06). 수령 컬럼은 A-06 에서 매핑한다.
 * 예약 항목은 IDENTITY 라 JPA batch insert 가 안 돼 JdbcTemplate 로 넣는다 (13-1 어필 포인트).
 */
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    /** 취소 마감: 픽업 시각 60분 전, 그 시각 포함 (R-13, 부록 B: 고정) */
    public static final int CANCEL_DEADLINE_MINUTES = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 9)
    private String reservationNo;                          // MMDD-NNNN, 판매일별 0001부터

    @Column(nullable = false)
    private long memberId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 4)
    private String phoneLast4;                             // 수령 검색용 역정규화

    @Column(nullable = false)
    private LocalDate saleDate;

    @Column(nullable = false)
    private long pickupSlotId;

    @Column(nullable = false)
    private LocalDateTime pickupAt;                        // 취소 마감 계산용 복사

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private ReservationStatus status;

    @Column(nullable = false)
    private int totalPrice;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private String idempotencyKey;                         // uk_reservation_idem: 중복 제출 최종 방어

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 취소 컬럼은 ReservationRepository.cancelIfReserved 의 조건부 UPDATE 로만 바꾼다 (엔티티 변경 감지로 덮어쓰지 않게)
    @Column(insertable = false, updatable = false)
    private LocalDateTime canceledAt;

    @Column(insertable = false, updatable = false)
    private Long canceledBy;

    @Column(insertable = false, updatable = false, length = 100)
    private String cancelReason;

    public static Reservation create(String reservationNo, long memberId, String phoneLast4, LocalDate saleDate,
                                     long pickupSlotId, LocalDateTime pickupAt, int totalPrice,
                                     String idempotencyKey, LocalDateTime now) {
        Reservation reservation = new Reservation();
        reservation.reservationNo = reservationNo;
        reservation.memberId = memberId;
        reservation.phoneLast4 = phoneLast4;
        reservation.saleDate = saleDate;
        reservation.pickupSlotId = pickupSlotId;
        reservation.pickupAt = pickupAt;
        reservation.status = ReservationStatus.RESERVED;
        reservation.totalPrice = totalPrice;
        reservation.idempotencyKey = idempotencyKey;
        reservation.createdAt = now;
        return reservation;
    }

    /**
     * 취소할 수 있는지 판단한다 (13-2 3단계, R-13). 엔티티는 바꾸지 않고, 확정은 조건부 UPDATE 가 한다.
     * 손님은 픽업 60분 전 그 시각까지(포함), 사장님 직권 취소(A-08)는 마감 검사가 없다.
     *
     * @throws BusinessException RESERVED 가 아니면 409, 손님이 마감 뒤에 취소하면 422
     */
    public Cancellation cancel(ReservationActor actor, LocalDateTime now, String reason) {
        if (status != ReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        if (!actor.admin() && now.isAfter(cancelDeadline())) {
            throw new BusinessException(ErrorCode.CANCEL_DEADLINE_PASSED);
        }
        return new Cancellation(now, actor.memberId(), reason);
    }

    /** 취소 판단 결과. 조건부 UPDATE·이력·응답에 그대로 쓴다 */
    public record Cancellation(LocalDateTime canceledAt, long canceledBy, String reason) {
    }

    public LocalDateTime cancelDeadline() {
        return pickupAt.minusMinutes(CANCEL_DEADLINE_MINUTES);
    }

    /** MMDD-NNNN (부록 B: 고정) */
    public static String formatNo(LocalDate saleDate, long sequence) {
        return "%02d%02d-%04d".formatted(saleDate.getMonthValue(), saleDate.getDayOfMonth(), sequence);
    }
}
