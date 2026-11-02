package com.fivexbread.reservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 업무 규칙을 통과한 예약 (ReservationRuleService → Redis 차감 → DB 저장).
 *
 * @param items breadId 오름차순. DB 조건부 UPDATE 를 이 순서로 해 데드락의 순환 대기를 없앤다 (13-1 어필 포인트)
 */
record ReservationPlan(
        long memberId,
        String phoneLast4,
        LocalDate saleDate,
        long pickupSlotId,
        LocalDateTime pickupAt,
        List<Item> items
) {

    /** @param perUserLimit null 이면 한도 없음 (R-04) */
    record Item(long breadId, String name, int unitPrice, int quantity, Integer perUserLimit) {
    }

    int totalPrice() {
        return items.stream().mapToInt(item -> item.unitPrice() * item.quantity()).sum();
    }
}
