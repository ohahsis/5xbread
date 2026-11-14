package com.fivexbread.saledate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 수량 재계산 결과 (A-12, M-11: 빵, Redis 값, DB 기준 값, 보정량).
 *
 * @param reconciliationId 재계산 기록(admin_action_log) id
 */
public record StockReconciliationResponse(long reconciliationId, LocalDate saleDate, OffsetDateTime reconciledAt,
                                          List<Item> breads) {

    /**
     * @param redisBefore Redis 잔여(보정 전). 키가 없었으면 null
     * @param dbRemaining DB 기준 잔여 = reservable_qty − reserved_qty
     * @param adjustment  더한 양(ADJUSTED 만 0 이 아님)
     */
    public record Item(long breadId, Integer redisBefore, int dbRemaining, int adjustment, Result result) {
    }

    public enum Result {
        /** 같음 */
        MATCHED,
        /** 차이만큼 INCRBY */
        ADJUSTED,
        /** 키가 없어 DB 기준으로 적재(예약이 끝나지 않은 판매일만) */
        LOADED,
        /** 키가 없고 마감이 지나 적재하지 않음 */
        NO_KEY,
        /** 두 관측의 차이가 달라(진행 중인 예약·취소) 보정하지 않음. 잠시 뒤 다시 누르면 된다 */
        CHANGING
    }
}
