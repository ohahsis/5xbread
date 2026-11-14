package com.fivexbread.saledate;

import java.time.LocalDate;
import java.util.Map;

/**
 * A-02 수량 늘리기의 Redis 반영 (아웃박스 payload). 수량 저장 트랜잭션 안에서 적는다.
 *
 * @param increases breadId → 늘린 예약 가능 총량(양수). 기존 행만. 새 행은 키가 없어 첫 예약 때 DB 기준으로 적재된다
 */
public record StockIncreaseEvent(LocalDate saleDate, Map<Long, Integer> increases) {

    public static final String TYPE = "STOCK_INCREASE";
}
