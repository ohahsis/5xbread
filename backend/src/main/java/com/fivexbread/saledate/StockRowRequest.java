package com.fivexbread.saledate;

import jakarta.validation.constraints.NotNull;

/**
 * PUT .../stocks 의 한 행 (A-02). 값이 비었는지만 여기서 400 으로 거른다.
 * 범위·행 안 비교·예약 수량 비교는 StockService 가 모든 행을 한 번에 검사해 422 로 돌려준다 (decisions.md 6장 R-07).
 *
 * @param perUserLimit null = 한도 없음 (R-04)
 */
public record StockRowRequest(
        @NotNull Long breadId,
        @NotNull Integer producedQty,
        @NotNull Integer reservableQty,
        Integer perUserLimit
) {
}
