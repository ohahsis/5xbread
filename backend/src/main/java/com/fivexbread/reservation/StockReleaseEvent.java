package com.fivexbread.reservation;

import java.time.LocalDate;
import java.util.Map;

/**
 * 취소·직권 취소의 Redis 복구 (아웃박스 payload, 13-2 4·5단계). 취소 트랜잭션 안에서 적는다.
 *
 * @param quantities breadId → 되돌릴 수량 (breadId 오름차순)
 * @param generation 취소 커밋 전에 읽은 user_qty 세대. null 이면 복구 때 user_qty 를 건드리지 않는다
 *                   (decisions.md 6장 "C-06 Redis 복구와 재적재")
 */
public record StockReleaseEvent(long reservationId, LocalDate saleDate, long memberId, Map<Long, Integer> quantities,
                                String generation) {

    public static final String TYPE = "STOCK_RELEASE";
}
