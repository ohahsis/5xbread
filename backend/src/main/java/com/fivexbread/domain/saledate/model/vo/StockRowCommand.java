package com.fivexbread.domain.saledate.model.vo;

/**
 * 수량 일괄 저장의 한 행 (A-02). 범위·행 안 비교·예약 수량 비교는 StockService 가 한다
 *
 * @param perUserLimit null = 한도 없음 (R-04)
 */
public record StockRowCommand(Long breadId, Integer producedQty, Integer reservableQty, Integer perUserLimit) {
}
