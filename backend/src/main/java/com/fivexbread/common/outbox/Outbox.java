package com.fivexbread.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 업무 트랜잭션 안에서 outbox_event 를 적는다 (13-2 4단계). Redis 는 부르지 않는다.
 * 업무가 롤백되면 이 행도 함께 사라지고, 커밋되면 앱이 바로 죽어도 행이 남아 폴러가 처리한다.
 * 트랜잭션 밖에서 부르면 업무와 따로 커밋돼 아웃박스의 의미가 없으므로 예외로 막는다
 * (@Transactional(MANDATORY) 와 같은 검사를 프록시 없이 한다).
 */
@Component
@RequiredArgsConstructor
public class Outbox {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** @return 이벤트 id. 커밋 후 OutboxRelay.publish 에 넘긴다 */
    public long append(String type, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("아웃박스는 업무 트랜잭션 안에서만 적는다 type=" + type);
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("아웃박스 payload 를 JSON 으로 바꾸지 못함 type=" + type, e);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO outbox_event (event_type, payload, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, type);
            statement.setString(2, json);
            statement.setObject(3, now);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
