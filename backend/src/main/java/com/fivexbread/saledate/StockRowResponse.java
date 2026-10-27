package com.fivexbread.saledate;

/**
 * 수량 표의 한 행 (M-06). 판매 중인 빵 전부 + 판매 중지됐지만 이 날 수량이 있는 빵.
 *
 * @param configured 이 날 수량 행이 있는지. 없으면 수량은 0, 한도는 null 로 보여 준다
 */
public record StockRowResponse(
        long breadId,
        String breadName,
        boolean active,
        boolean configured,
        int producedQty,
        int reservableQty,
        int reservedQty,
        Integer perUserLimit
) {
}
