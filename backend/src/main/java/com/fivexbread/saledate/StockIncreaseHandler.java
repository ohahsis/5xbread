package com.fivexbread.saledate;

import com.fivexbread.common.outbox.OutboxEvent;
import com.fivexbread.common.outbox.OutboxHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** A-02 늘리기 이벤트를 Redis 잔여에 INCRBY (decisions.md 6장 "A-02 Redis 반영 순서" ④) */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockIncreaseHandler implements OutboxHandler<StockIncreaseEvent> {

    private final StockRedisSync stockRedisSync;

    @Override
    public String type() {
        return StockIncreaseEvent.TYPE;
    }

    @Override
    public Class<StockIncreaseEvent> payloadType() {
        return StockIncreaseEvent.class;
    }

    @Override
    public void handle(OutboxEvent event, StockIncreaseEvent payload) {
        if (!stockRedisSync.increaseOnce(event.id(), event.createdAt(), payload.saleDate(), payload.increases())) {
            log.info("이미 적용한 수량 늘리기 이벤트 eventId={}", event.id());
        }
    }
}
