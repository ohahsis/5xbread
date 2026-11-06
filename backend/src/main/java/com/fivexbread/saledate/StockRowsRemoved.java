package com.fivexbread.saledate;

import java.time.LocalDate;
import java.util.List;

/** 수량 행을 지웠다는 이벤트 (휴무일 전환). 커밋 후 StockRedisSync 가 재고 키를 지운다 */
public record StockRowsRemoved(LocalDate saleDate, List<Long> breadIds) {
}
