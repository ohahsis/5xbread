package com.fivexbread.saledate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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

    /*
     * 적재: 재고 키가 없을 때만 DB 기준 잔여로 만들고, 만든 키에만 적재 시각을 함께 적는다. 이미 있는 키는 둘 다 그대로.
     * KEYS: 재고 키 n개 + 적재 시각 키 n개 / ARGV: TTL(밀리초), 적재 시각(epoch 밀리초), 잔여 n개
     */
    private static final RedisScript<Long> FILL = RedisScript.of("""
            local n = #KEYS / 2
            local filled = 0
            for i = 1, n do
              if redis.call('SET', KEYS[i], ARGV[i+2], 'NX', 'PX', ARGV[1]) then
                redis.call('SET', KEYS[n+i], ARGV[2], 'PX', ARGV[1])
                filled = filled + 1
              end
            end
            return filled
            """, Long.class);

    /**
     * 아웃박스 이벤트의 재고 복구 한 줄 (Lua 함수). 키가 있고, 그 키의 적재 시각이 이벤트 생성 시각보다 늦지 않을 때만 INCRBY.
     * 적재 시각이 더 늦으면 적재한 DB 값에 이 변경이 이미 들어 있어 더하면 두 번이 된다 (decisions.md 6장 "아웃박스 지연 처리와 재적재").
     * 같은 밀리초는 더한다. 실제로는 재적재와 커밋이 같은 밀리초에 겹칠 때만이고, 시계를 멈춘 테스트에서는 늘 같다.
     * 적재 시각 키가 없으면 이 기능 전에 적재된 키로 보고 더한다. 취소 복구(ReservationStockStore)도 같은 함수를 쓴다.
     */
    public static final String LUA_RESTORE_IF_LOADED_BEFORE = """
            local function restoreIfLoadedBefore(stockKey, loadedKey, amount, createdAt)
              if redis.call('EXISTS', stockKey) == 0 then return 0 end
              local loaded = redis.call('GET', loadedKey)
              if loaded and tonumber(loaded) > tonumber(createdAt) then return 0 end
              redis.call('INCRBY', stockKey, amount)
              return 1
            end
            """;

    /*
     * A-02 늘리기 (아웃박스 소비). applied 키를 SET NX 로 잡은 경우에만 적용해 같은 이벤트가 다시 와도 한 번만 더한다.
     * KEYS: 재고 키 n개 + 적재 시각 키 n개 + applied 키 / ARGV: 이벤트 생성 시각(epoch 밀리초), applied TTL(초), 늘릴 양 n개
     * 반환: 1 적용, 0 이미 적용한 이벤트
     */
    private static final RedisScript<Long> INCREASE_ONCE = RedisScript.of(LUA_RESTORE_IF_LOADED_BEFORE + """
            local n = (#KEYS - 1) / 2
            if not redis.call('SET', KEYS[#KEYS], '1', 'NX', 'EX', ARGV[2]) then return 0 end
            for i = 1, n do restoreIfLoadedBefore(KEYS[i], KEYS[n+i], ARGV[i+2], ARGV[1]) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    /**
     * DB 기준 잔여로 빠진 재고 키를 채운다 (decisions.md 6장 "C-04 Redis 재고 적재"). 예약과 목록 조회가 함께 쓴다.
     * NX 라 앱 여러 개가 동시에 채워도 먼저 쓴 값을 덮지 않는다.
     * 새로 만든 키에는 적재 시각을 함께 적는다. 반드시 DB 를 읽은 뒤에 불러야 한다: 적재 시각이 DB 읽기보다 늦어야
     * "적재 시각 이후에 생성된 이벤트만 더한다"가 어긋나도 덜 더하는(덜 팔리는) 쪽이 된다.
     *
     * @param remaining breadId → DB 기준 잔여
     */
    public void fillMissing(LocalDate saleDate, Map<Long, Integer> remaining) {
        if (remaining.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> keys = new ArrayList<>();
        List<String> loadedKeys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(StockKeys.ttl(saleDate, now).toMillis()));
        args.add(String.valueOf(StockKeys.epochMillis(now)));
        remaining.forEach((breadId, quantity) -> {
            keys.add(StockKeys.stock(saleDate, breadId));
            loadedKeys.add(StockKeys.loadedAt(saleDate, breadId));
            args.add(String.valueOf(quantity));
        });
        keys.addAll(loadedKeys);
        redisTemplate.execute(FILL, keys, args.toArray());
    }

    /**
     * 수량 행이 지워지면(휴무일 전환) 재고 키도 지운다. 남겨 두면 나중에 같은 빵의 행을 새로 만들 때
     * A-02 가 "새 행"으로 보고 Redis 를 건드리지 않아 옛 잔여가 살아난다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockRowsRemoved(StockRowsRemoved event) {
        try {
            List<String> keys = new ArrayList<>();
            event.breadIds().forEach(id -> {
                keys.add(StockKeys.stock(event.saleDate(), id));
                keys.add(StockKeys.loadedAt(event.saleDate(), id));
            });
            redisTemplate.delete(keys);
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

    /**
     * A-02 늘리기의 아웃박스 소비 (StockIncreaseHandler). 같은 이벤트는 한 번만 적용한다.
     * 키가 없는 빵, 이벤트 생성 뒤에 다시 적재된 빵은 건너뛴다.
     *
     * @param amounts breadId → 늘릴 양(양수)
     * @return 적용했으면 true, 이미 적용한 이벤트면 false
     */
    public boolean increaseOnce(long eventId, LocalDateTime createdAt, LocalDate saleDate, Map<Long, Integer> amounts) {
        List<String> keys = new ArrayList<>();
        List<String> loadedKeys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(StockKeys.epochMillis(createdAt)));
        args.add(String.valueOf(StockKeys.ttl(saleDate, LocalDateTime.now(clock)).toSeconds()));
        amounts.forEach((breadId, amount) -> {
            keys.add(StockKeys.stock(saleDate, breadId));
            loadedKeys.add(StockKeys.loadedAt(saleDate, breadId));
            args.add(String.valueOf(amount));
        });
        keys.addAll(loadedKeys);
        keys.add(StockKeys.applied(saleDate, eventId));
        return Long.valueOf(1).equals(redisTemplate.execute(INCREASE_ONCE, keys, args.toArray()));
    }

    /** 줄이기 보상(DB 실패 시). 키가 없는 빵은 건너뛴다. @param amounts breadId → 늘릴 양(양수) */
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
