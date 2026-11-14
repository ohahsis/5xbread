package com.fivexbread.common.outbox;

import java.time.LocalDateTime;

/**
 * outbox_event 한 행. createdAt 은 업무 트랜잭션 안에서 INSERT 한 시각(커밋보다 조금 앞선다).
 * 재고 복구는 이 시각과 재고 키의 적재 시각을 비교한다 (decisions.md 6장 "아웃박스 지연 처리와 재적재").
 */
public record OutboxEvent(long id, String type, String payload, LocalDateTime createdAt) {
}
