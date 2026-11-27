package com.fivexbread.domain.saledate.model.vo;

import com.fivexbread.domain.saledate.model.SaleDate;
import com.fivexbread.domain.saledate.model.enums.SaleDateStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 손님용 판매일 상태 (C-02). 상태는 조회 시각 기준 */
public record SaleDateStatusResult(LocalDate saleDate, SaleDateStatus status, LocalDateTime openAt, LocalDateTime closeAt) {

    public static SaleDateStatusResult of(SaleDate saleDate, LocalDateTime now) {
        return new SaleDateStatusResult(saleDate.getSaleDate(), saleDate.status(now), saleDate.getOpenAt(),
                saleDate.getCloseAt());
    }
}
