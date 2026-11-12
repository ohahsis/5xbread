package com.fivexbread.saledate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 사장님 수량 변경을 Redis 잔여에 반영 (decisions.md 6장 "A-02 Redis 반영").
 * 재고 키가 없으면 건드리지 않는다. 첫 예약 때 DB 기준으로 적재된다 ("C-04 Redis 재고 적재").
 * 키가 없을 때 INCRBY·DECRBY 를 그대로 보내면 0에서 시작하는 틀린 키가 생기므로, 존재 확인과 변경을 Lua 한 번에 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRedisSync {

    /*
     * 줄이기: 키가 있는 빵의 잔여가 모두 줄일 양 이상일 때만 전부 DECRBY. 하나라도 모자라면 아무것도 바꾸지 않는다.
     * KEYS: 재고 키들 / ARGV: [breadId, 줄일 양] 반복
     * 반환: {'OK', 줄인 breadId...} 또는 {'FAILED', 'breadId:잔여'...}
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DECREASE = RedisScript.of("""
            local fails = {}
            for i = 1, #KEYS do
              local remaining = redis.call('GET', KEYS[i])
              if remaining and tonumber(remaining) < tonumber(ARGV[i*2]) then
                table.insert(fails, ARGV[i*2-1] .. ':' .. remaining)
              end
            end
            if #fails > 0 then
              table.insert(fails, 1, 'FAILED')
              return fails
            end
            local done = {'OK'}
            for i = 1, #KEYS do
              if redis.call('EXISTS', KEYS[i]) == 1 then
                redis.call('DECRBY', KEYS[i], ARGV[i*2])
                table.insert(done, ARGV[i*2-1])
              end
            end
            return done
            """, List.class);

    /* 늘리기·보상: 키가 있는 것만 INCRBY. KEYS: 재고 키들 / ARGV: 늘릴 양들 */
    private static final RedisScript<Long> INCREASE = RedisScript.of("""
            local changed = 0
            for i = 1, #KEYS do
              if redis.call('EXISTS', KEYS[i]) == 1 then
                redis.call('INCRBY', KEYS[i], ARGV[i])
                changed = changed + 1
              end
            end
            return changed
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    /**
     * DB 기준 잔여로 빠진 재고 키를 채운다 (decisions.md 6장 "C-04 Redis 재고 적재"). 예약과 목록 조회가 함께 쓴다.
     * NX 라 앱 여러 개가 동시에 채워도 먼저 쓴 값을 덮지 않는다.
     *
     * @param remaining breadId → DB 기준 잔여
     */
    public void fillMissing(LocalDate saleDate, Map<Long, Integer> remaining) {
        Expiration expiration = Expiration.from(StockKeys.ttl(saleDate, LocalDateTime.now(clock)));
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            remaining.forEach((breadId, quantity) -> connection.stringCommands().set(
                    StockKeys.stock(saleDate, breadId).getBytes(StandardCharsets.UTF_8),
                    String.valueOf(quantity).getBytes(StandardCharsets.UTF_8), expiration, SetOption.ifAbsent()));
            return null;
        });
    }

    /**
     * 수량 행이 지워지면(휴무일 전환) 재고 키도 지운다. 남겨 두면 나중에 같은 빵의 행을 새로 만들 때
     * A-02 가 "새 행"으로 보고 Redis 를 건드리지 않아 옛 잔여가 살아난다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockRowsRemoved(StockRowsRemoved event) {
        try {
            redisTemplate.delete(event.breadIds().stream().map(id -> StockKeys.stock(event.saleDate(), id)).toList());
        } catch (RuntimeException e) {
            log.error("지운 수량 행의 재고 키 삭제 실패. 옛 잔여가 남음 saleDate={} breadIds={}", event.saleDate(),
                    event.breadIds(), e);
        }
    }

    /**
     * @param amounts breadId → 줄일 양(양수)
     * @return 성공이면 실제로 줄인 빵(키가 있던 빵), 실패면 잔여가 모자란 빵의 Redis 잔여
     */
    public DecreaseResult decrease(LocalDate saleDate, Map<Long, Integer> amounts) {
        if (amounts.isEmpty()) {
            return new DecreaseResult(List.of(), Map.of());
        }
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        amounts.forEach((breadId, amount) -> {
            keys.add(StockKeys.stock(saleDate, breadId));
            args.add(String.valueOf(breadId));
            args.add(String.valueOf(amount));
        });
        List<?> result = redisTemplate.execute(DECREASE, keys, args.toArray());
        List<String> values = result.stream().map(String::valueOf).toList();
        if ("OK".equals(values.get(0))) {
            return new DecreaseResult(values.subList(1, values.size()).stream().map(Long::valueOf).toList(), Map.of());
        }
        Map<Long, Integer> shortages = new LinkedHashMap<>();
        values.subList(1, values.size()).forEach(value -> {
            String[] parts = value.split(":");
            shortages.put(Long.valueOf(parts[0]), Integer.valueOf(parts[1]));
        });
        return new DecreaseResult(List.of(), shortages);
    }

    /** @param amounts breadId → 늘릴 양(양수). 키가 없는 빵은 건너뛴다 */
    public void increase(LocalDate saleDate, Map<Long, Integer> amounts) {
        if (amounts.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        amounts.forEach((breadId, amount) -> {
            keys.add(StockKeys.stock(saleDate, breadId));
            args.add(String.valueOf(amount));
        });
        redisTemplate.execute(INCREASE, keys, args.toArray());
    }

    /**
     * @param decreased 실제로 줄인 빵 (DB 저장이 실패하면 이 빵만 되돌린다)
     * @param shortages 실패했을 때 잔여가 모자란 빵 → Redis 잔여
     */
    public record DecreaseResult(List<Long> decreased, Map<Long, Integer> shortages) {

        public boolean failed() {
            return !shortages.isEmpty();
        }
    }
}
