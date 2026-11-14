package com.fivexbread.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 이벤트 처리 (8장 OutboxRelay). 트랜잭션 없이 돈다(Redis 를 부르므로).
 * <ul>
 *   <li>publish(id): 커밋 직후 업무 Facade 가 부른다. 실패해도 예외를 밖으로 내지 않고 행을 남겨 폴러에 맡긴다</li>
 *   <li>publishPending(): 폴러(OutboxRelayJob)가 부른다. 생성 후 relayDelay 가 지난 미발행 행을 id 순으로</li>
 * </ul>
 * 처리 = 핸들러 적용(Redis Lua, applied 키로 멱등) → published_at 조건부 UPDATE. 두 단계 사이에 앱이 죽거나 DB UPDATE 가
 * 실패하면 같은 이벤트가 다시 처리되는데, applied 키가 있어 Redis 는 한 번만 바뀐다 (최소 1회 전달 + 멱등 소비).
 */
@Slf4j
@Component
public class OutboxRelay {

    private static final RowMapper<OutboxEvent> ROW_MAPPER = (rs, rowNum) -> new OutboxEvent(rs.getLong("id"),
            rs.getString("event_type"), rs.getString("payload"), rs.getObject("created_at", LocalDateTime.class));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final Clock clock;
    private final Map<String, OutboxHandler<?>> handlers;

    public OutboxRelay(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, OutboxProperties properties, Clock clock,
                       List<OutboxHandler<?>> handlers) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::type, Function.identity()));
    }

    /** 커밋 후 바로 처리. 실패하면 WARN 만 남기고 폴러가 relayDelay 뒤에 다시 시도한다 */
    public void publish(long eventId) {
        try {
            List<OutboxEvent> events = jdbcTemplate.query("""
                    SELECT id, event_type, payload, created_at FROM outbox_event
                    WHERE id = ? AND published_at IS NULL""", ROW_MAPPER, eventId);
            events.forEach(this::process);
        } catch (RuntimeException e) {
            log.warn("아웃박스 바로 처리 실패. 폴러가 다시 처리함 eventId={}", eventId, e);
        }
    }

    /**
     * 폴러 1회분. 생성 후 relayDelay 가 지난 미발행 행을 id 순으로 batchSize 씩, 최대 maxBatches 묶음.
     * Redis 연결 실패·시간 초과면 남은 행도 같은 이유로 실패하므로 바로 멈춘다(행마다 시간 제한만큼 기다리지 않게).
     * 그 밖의 실패(잘못된 payload 등)는 ERROR 를 남기고 다음 행으로 넘어간다. 그 행은 다음 주기에 다시 시도된다.
     *
     * @return 이번에 발행한 수
     */
    public int publishPending() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(properties.relayDelay());
        long lastId = 0;
        int published = 0;
        for (int batch = 0; batch < properties.maxBatches(); batch++) {
            // ix_outbox_pending(published_at, id): published_at IS NULL 범위를 id 순으로 읽는다
            List<OutboxEvent> events = jdbcTemplate.query("""
                    SELECT id, event_type, payload, created_at FROM outbox_event
                    WHERE published_at IS NULL AND id > ? AND created_at <= ?
                    ORDER BY id LIMIT ?""", ROW_MAPPER, lastId, cutoff, properties.batchSize());
            for (OutboxEvent event : events) {
                lastId = event.id();
                try {
                    process(event);
                    published++;
                } catch (RedisConnectionFailureException | QueryTimeoutException e) {
                    log.warn("아웃박스 폴러: Redis 장애로 중단. 다음 주기에 이어서 처리 eventId={} published={}",
                            event.id(), published);
                    return published;
                } catch (RuntimeException e) {
                    log.error("아웃박스 이벤트 처리 실패. 다음 주기에 다시 시도 eventId={} type={}", event.id(),
                            event.type(), e);
                }
            }
            if (events.size() < properties.batchSize()) {
                break;
            }
        }
        if (published > 0) {
            log.info("아웃박스 폴러 발행 {}건", published);
        }
        return published;
    }

    /**
     * 보관 기간이 지난 발행 행을 1,000행씩 지운다. 미발행 행은 지우지 않는다(published_at < ? 는 NULL 을 고르지 않음).
     *
     * @return 지운 행 수
     */
    public int deletePublished() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(properties.retention());
        int total = 0;
        int deleted;
        do {
            deleted = jdbcTemplate.update(
                    "DELETE FROM outbox_event WHERE published_at < ? ORDER BY published_at LIMIT 1000", cutoff);
            total += deleted;
        } while (deleted == 1000);
        if (total > 0) {
            log.info("발행한 아웃박스 행 {}건 정리 (published_at < {})", total, cutoff);
        }
        return total;
    }

    private void process(OutboxEvent event) {
        OutboxHandler<?> handler = handlers.get(event.type());
        if (handler == null) {
            throw new IllegalStateException("처리할 수 없는 아웃박스 이벤트 type=" + event.type());
        }
        handle(handler, event);
        jdbcTemplate.update("UPDATE outbox_event SET published_at = ? WHERE id = ? AND published_at IS NULL",
                LocalDateTime.now(clock), event.id());
    }

    private <T> void handle(OutboxHandler<T> handler, OutboxEvent event) {
        T payload;
        try {
            payload = objectMapper.readValue(event.payload(), handler.payloadType());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("아웃박스 payload 를 읽지 못함 eventId=" + event.id(), e);
        }
        handler.handle(event, payload);
    }
}
