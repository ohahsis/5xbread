package com.fivexbread.reservation;

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
 * V1 reservation 테이블 (C-04). 취소·수령 컬럼은 C-06·A-06 에서 매핑한다.
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

    public LocalDateTime cancelDeadline() {
        return pickupAt.minusMinutes(CANCEL_DEADLINE_MINUTES);
    }

    /** MMDD-NNNN (부록 B: 고정) */
    public static String formatNo(LocalDate saleDate, long sequence) {
        return "%02d%02d-%04d".formatted(saleDate.getMonthValue(), saleDate.getDayOfMonth(), sequence);
    }
}
