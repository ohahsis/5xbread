package com.fivexbread.inbound.controller.saledate.model.response;

import com.fivexbread.domain.saledate.model.vo.StockSheet;
import com.fivexbread.inbound.controller.saledate.model.response.StockRowResponse;
import java.time.LocalDate;
import java.util.List;

/** GET·PUT .../stocks 응답 본문. 버전은 ETag 헤더로 내보낸다 */
public record StocksResponse(LocalDate saleDate, List<StockRowResponse> rows) {

    public static StocksResponse from(StockSheet sheet) {
        return new StocksResponse(sheet.saleDate(), sheet.rows().stream().map(StockRowResponse::from).toList());
    }
}
