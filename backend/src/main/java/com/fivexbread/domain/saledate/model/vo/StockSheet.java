package com.fivexbread.domain.saledate.model.vo;

import java.time.LocalDate;
import java.util.List;

/** 판매일의 수량 표 (M-06). 판매 중인 빵 전부 + 판매 중지됐지만 이 날 수량이 있는 빵, 노출 순서 */
public record StockSheet(LocalDate saleDate, List<Row> rows) {

    /** @param configured 이 날 수량 행이 있는지. 없으면 수량은 0, 한도는 null */
    public record Row(long breadId, String breadName, boolean active, boolean configured, int producedQty,
                      int reservableQty, int reservedQty, Integer perUserLimit) {
    }
}
