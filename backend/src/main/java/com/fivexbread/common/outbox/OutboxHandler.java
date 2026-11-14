package com.fivexbread.common.outbox;

/**
 * 이벤트 종류별 소비자. 최소 1회 전달이라 같은 이벤트가 두 번 올 수 있으므로 handle 은 멱등해야 한다
 * (Redis Lua 안에서 applied 키를 SET NX 로 잡은 뒤에만 적용).
 *
 * @param <T> payload JSON 을 읽을 타입
 */
public interface OutboxHandler<T> {

    String type();

    Class<T> payloadType();

    void handle(OutboxEvent event, T payload);
}
