package com.fivexbread.saledate;

import java.time.LocalDate;
import java.util.List;

/** GET·PUT .../stocks 응답 본문. 버전은 ETag 헤더로 내보낸다 */
public record StocksResponse(LocalDate saleDate, List<StockRowResponse> rows) {
}
