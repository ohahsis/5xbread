package com.fivexbread.reservation;

import com.fivexbread.common.outbox.OutboxRelay;
import com.fivexbread.reservation.ReservationCancelService.Canceled;
import com.fivexbread.reservation.ReservationCancelService.Target;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 예약 취소 순서 (13-2, C-06·A-08). 수량 복구는 반드시 DB 커밋 → Redis.
 * 반대로 하면 DB 가 실패했을 때 Redis 잔여가 실제보다 많아져 초과 예약을 받을 수 있다 (13-2 어필 포인트).
 *
 * 1. 예약 찾기(404) → 2. Redis user_qty 세대 읽기 → 3. DB 트랜잭션(409·422, outbox_event 포함) → 4. 커밋 후 바로 Redis 복구
 * 4가 실패하거나 그 전에 앱이 죽어도 outbox_event 가 남아 폴러가 복구한다 (decisions.md 6장 "아웃박스 흐름").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationCancelFacade {

    private final ReservationCancelService cancelService;
    private final ReservationStockStore stockStore;
    private final OutboxRelay outboxRelay;

    public ReservationResponse cancel(long reservationId, ReservationActor actor, String reason) {
        Target target = cancelService.target(reservationId, actor);
        String generation = generation(target);
        Canceled canceled = cancelService.cancel(reservationId, actor, reason, generation);
        // 커밋 후 처리. 실패해도 취소는 확정됐으므로 200 (행이 남아 폴러가 처리). 3단계에서 잔여 변경 발행(C-03)도 여기에 붙인다
        outboxRelay.publish(canceled.outboxEventId());
        return canceled.response();
    }

    /**
     * 커밋 전 user_qty 세대 (decisions.md 6장 "C-06 Redis 복구와 재적재"). 커밋 후 그사이 DB 기준으로 다시 적재된 해시에서
     * 한 번 더 빼지 않으려고 쓴다. Redis 에 닿지 않으면 null: 복구 때 user_qty 를 건드리지 않아 더 엄격한 쪽으로 남는다.
     */
    private String generation(Target target) {
        try {
            return stockStore.generation(target.saleDate(), target.memberId());
        } catch (RuntimeException e) {
            log.warn("취소 전 user_qty 세대를 읽지 못함 saleDate={} memberId={}", target.saleDate(), target.memberId(), e);
            return null;
        }
    }
}
