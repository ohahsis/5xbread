package com.fivexbread.saledate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * V1 daily_stock 테이블 (A-02). reserved_qty 는 예약·취소가 조건부 UPDATE 로 바꾸므로 엔티티로 고치지 않는다.
 * 행 버전은 두지 않는다. 예약이 몰릴 때 사장님 저장이 계속 충돌하기 때문 (decisions.md 6장 "9장 daily_stock.version")
 */
@Entity
@Table(name = "daily_stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate saleDate;

    @Column(nullable = false)
    private long breadId;

    @Column(nullable = false)
    private int producedQty;

    @Column(nullable = false)
    private int reservableQty;

    @Column(nullable = false, insertable = false, updatable = false)
    private int reservedQty;

    private Integer perUserLimit;                          // NULL = 한도 없음 (R-04)

    /** 처음 설정하는 행. 예약 수량은 DB 기본값 0 */
    public static DailyStock create(LocalDate saleDate, long breadId, int producedQty, int reservableQty,
                                    Integer perUserLimit) {
        DailyStock stock = new DailyStock();
        stock.saleDate = saleDate;
        stock.breadId = breadId;
        stock.producedQty = producedQty;
        stock.reservableQty = reservableQty;
        stock.perUserLimit = perUserLimit;
        return stock;
    }
}
