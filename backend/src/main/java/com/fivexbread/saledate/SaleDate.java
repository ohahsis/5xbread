package com.fivexbread.saledate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * V1 sale_date 테이블 (A-04). 판매일 자체가 PK.
 * version 은 수량 설정 ETag(A-02)이고 @Version 이 아니다. 수량 저장은 이 행을 바꾸지 않아 @Version 이 올라가지 않기 때문에,
 * SaleDateRepository.increaseVersion 의 조건부 UPDATE 로만 비교·증가한다 (decisions.md 6장).
 * insertable·updatable=false: 판매일 설정을 저장할 때 Hibernate 가 읽어 둔 옛 version 으로 덮어쓰지 않게 한다.
 */
@Entity
@Table(name = "sale_date")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleDate {

    @Id
    private LocalDate saleDate;

    @Column(nullable = false)
    private LocalDateTime openAt;

    @Column(nullable = false)
    private LocalDateTime closeAt;

    @Column(nullable = false)
    private boolean holiday;

    @Column(nullable = false, insertable = false, updatable = false)
    private int version;

    private SaleDate(LocalDate saleDate, LocalDateTime openAt, LocalDateTime closeAt, boolean holiday) {
        this.saleDate = saleDate;
        this.openAt = openAt;
        this.closeAt = closeAt;
        this.holiday = holiday;
    }

    public static SaleDate create(LocalDate saleDate, LocalDateTime openAt, LocalDateTime closeAt, boolean holiday) {
        return new SaleDate(saleDate, openAt, closeAt, holiday);
    }

    /** PUT 이라 통째로 바꾼다. 규칙 검사는 SaleDateRules 가 끝낸 뒤 부른다 */
    public void replace(LocalDateTime openAt, LocalDateTime closeAt, boolean holiday) {
        this.openAt = openAt;
        this.closeAt = closeAt;
        this.holiday = holiday;
    }

    /** 예약 오픈 시각이 지났는지 (오픈 후에는 오픈 시각을 바꿀 수 없고 마감은 앞당기기만 가능) */
    public boolean isOpened(LocalDateTime now) {
        return !now.isBefore(openAt);
    }

    /** 손님에게 보여 주는 상태. 예약 기간은 [오픈, 마감) (R-11, decisions.md 6장) */
    public SaleDateStatus status(LocalDateTime now) {
        if (holiday) {
            return SaleDateStatus.HOLIDAY;
        }
        if (!isOpened(now)) {
            return SaleDateStatus.NOT_OPEN;
        }
        return now.isBefore(closeAt) ? SaleDateStatus.OPEN : SaleDateStatus.CLOSED;
    }

    public String eTag() {
        return eTagOf(version);
    }

    static String eTagOf(int version) {
        return "\"v" + version + "\"";
    }
}
