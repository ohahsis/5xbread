package com.fivexbread.reservation;

import java.util.List;

/**
 * Redis 차감은 통과했지만 DB 조건부 UPDATE 가 0행 (Redis 잔여가 DB 보다 많았음). 트랜잭션을 롤백시키고,
 * Facade 가 Redis 를 보상한 뒤 409 OUT_OF_STOCK 으로 바꾼다 (decisions.md 6장 "C-04 DB 조건부 UPDATE 0행").
 */
class StockShortageException extends RuntimeException {

    private final List<Long> breadIds;

    StockShortageException(List<Long> breadIds) {
        super("DB 기준 잔여 부족 breadIds=" + breadIds);
        this.breadIds = List.copyOf(breadIds);
    }

    List<Long> breadIds() {
        return breadIds;
    }
}
