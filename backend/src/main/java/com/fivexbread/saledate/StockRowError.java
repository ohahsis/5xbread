package com.fivexbread.saledate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 422 INVALID_STOCK_ROWS 의 errors[] 한 항목 (13-8 응답 예시).
 *
 * @param reserved STOCK_BELOW_RESERVED 일 때 현재 예약 수량
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StockRowError(Long breadId, String field, Reason reason, Integer reserved) {

    public enum Reason {
        DUPLICATE_BREAD,          // 같은 빵이 두 번
        BREAD_NOT_FOUND,
        BREAD_INACTIVE,           // 판매 중지된 빵은 새로 설정할 수 없음 (이미 있는 행은 수정 가능)
        OUT_OF_RANGE,             // 생산량 0~500, 총량 0 이상, 한도 1~20
        EXCEEDS_PRODUCED,         // 총량 > 생산량 (R-01)
        STOCK_BELOW_RESERVED      // 총량 < 현재 예약 수량 (R-07)
    }

    static StockRowError of(Long breadId, String field, Reason reason) {
        return new StockRowError(breadId, field, reason, null);
    }
}
