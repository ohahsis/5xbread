package com.fivexbread.reservation;

import com.fivexbread.common.outbox.OutboxEvent;
import com.fivexbread.common.outbox.OutboxHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 취소 이벤트를 Redis 재고·user_qty 에 되돌린다 (13-2 5단계) */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationReleaseHandler implements OutboxHandler<StockReleaseEvent> {

    private final ReservationStockStore stockStore;

    @Override
    public String type() {
        return StockReleaseEvent.TYPE;
    }

    @Override
    public Class<StockReleaseEvent> payloadType() {
        return StockReleaseEvent.class;
    }

    @Override
    public void handle(OutboxEvent event, StockReleaseEvent payload) {
        if (!stockStore.releaseOnce(event.id(), event.createdAt(), payload.saleDate(), payload.memberId(),
                payload.quantities(), payload.generation())) {
            log.info("이미 적용한 취소 복구 이벤트 eventId={} reservationId={}", event.id(), payload.reservationId());
        }
    }
}
