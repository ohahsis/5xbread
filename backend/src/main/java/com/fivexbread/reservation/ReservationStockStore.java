package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.saledate.BreadQuantity;
import com.fivexbread.saledate.DailyStockRepository;
import com.fivexbread.saledate.StockKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 예약 수량의 Redis 검사·차감과 보상 (13-1 5·8단계, N-01~N-03).
 * - stock:{date}:{breadId}: 잔여
 * - user_qty:{date}:{memberId}: 빵별 누적 수량 + count(그날 예약 건수, 하루 3건) + gen(적재 세대)
 * 키가 없으면 DB 기준으로 SET NX·HSETNX 로 채우고 다시 실행한다 (decisions.md 6장 "C-04 Redis 재고 적재").
 *
 * gen 은 user_qty 를 DB 에서 적재할 때마다 새로 만드는 토큰이다. 되돌리기(보상·취소)는 차감했던(또는 커밋 전에 읽어 둔) 세대와
 * 지금 해시의 세대가 같을 때만 user_qty 를 뺀다. 그사이 해시가 사라졌다가 DB 기준으로 다시 적재됐다면 그 값에는 이미 DB 상태가
 * 들어 있어, 한 번 더 빼면 1인 한도·하루 건수가 실제보다 느슨해진다 (decisions.md 6장 "C-06 Redis 복구와 재적재").
 */
@Component
@RequiredArgsConstructor
public class ReservationStockStore {

    private static final int MAX_ATTEMPTS = 3;

    /*
     * 모든 빵의 잔여·1인 한도와 하루 건수를 먼저 검사하고, 하나라도 실패하면 아무것도 바꾸지 않는다 (R-06 all-or-nothing).
     * KEYS: 재고 키 n개 + user_qty 키 / ARGV[1]: 하루 건수 한도, 이어서 [breadId, 수량, 한도(-1=없음)] 반복
     * 반환: {'OK', gen} / {'MISSING', breadId 또는 'USER'...} / {'COUNT_EXCEEDED'} / {'FAILED', 'breadId:사유:남은 수량'...}
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE = RedisScript.of("""
            local n = #KEYS - 1
            local userKey = KEYS[#KEYS]
            local missing = {}
            for i = 1, n do
              if redis.call('EXISTS', KEYS[i]) == 0 then table.insert(missing, ARGV[i*3-1]) end
            end
            if redis.call('HEXISTS', userKey, 'count') == 0 then table.insert(missing, 'USER') end
            if #missing > 0 then
              table.insert(missing, 1, 'MISSING')
              return missing
            end
            if tonumber(redis.call('HGET', userKey, 'count')) >= tonumber(ARGV[1]) then
              return {'COUNT_EXCEEDED'}
            end
            local fails = {}
            for i = 1, n do
              local breadId, qty, limit = ARGV[i*3-1], tonumber(ARGV[i*3]), tonumber(ARGV[i*3+1])
              local remaining = tonumber(redis.call('GET', KEYS[i]))
              if remaining < qty then
                table.insert(fails, breadId .. ':OUT_OF_STOCK:' .. math.max(remaining, 0))
              end
              local mine = tonumber(redis.call('HGET', userKey, breadId) or '0')
              if limit >= 0 and mine + qty > limit then
                table.insert(fails, breadId .. ':PER_USER_LIMIT_EXCEEDED:' .. math.max(limit - mine, 0))
              end
            end
            if #fails > 0 then
              table.insert(fails, 1, 'FAILED')
              return fails
            end
            for i = 1, n do
              redis.call('DECRBY', KEYS[i], ARGV[i*3])
              redis.call('HINCRBY', userKey, ARGV[i*3-1], ARGV[i*3])
            end
            redis.call('HINCRBY', userKey, 'count', 1)
            return {'OK', redis.call('HGET', userKey, 'gen') or ''}
            """, List.class);

    /*
     * 차감을 되돌린다 (C-04 보상, C-06·A-08 취소). 재고 키가 없으면 건드리지 않는다.
     * 없는 키에 INCRBY 하면 0에서 시작하는 틀린 키가 생기기 때문. 그 키는 다음 예약 때 DB 기준으로 다시 채워진다.
     * user_qty 는 세대가 ARGV[1]과 같을 때만 뺀다(빈 값이면 빼지 않음).
     * KEYS: 재고 키 n개 + user_qty 키 / ARGV[1]: 세대, 이어서 [breadId, 수량] 반복
     */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            local n = #KEYS - 1
            local userKey = KEYS[#KEYS]
            for i = 1, n do
              if redis.call('EXISTS', KEYS[i]) == 1 then redis.call('INCRBY', KEYS[i], ARGV[i*2+1]) end
            end
            if ARGV[1] ~= '' and redis.call('HGET', userKey, 'gen') == ARGV[1] then
              for i = 1, n do redis.call('HINCRBY', userKey, ARGV[i*2], -tonumber(ARGV[i*2+1])) end
              redis.call('HINCRBY', userKey, 'count', -1)
            end
            return 1
            """, Long.class);

    /*
     * user_qty 적재. 이미 적재됐으면(count 필드 있음) 그대로 둔다.
     * KEYS[1]: user_qty / ARGV: TTL(초), 건수, 세대, [breadId, 수량] 반복
     */
    private static final RedisScript<Long> LOAD_USER = RedisScript.of("""
            if redis.call('HEXISTS', KEYS[1], 'count') == 1 then return 0 end
            for i = 4, #ARGV, 2 do redis.call('HSETNX', KEYS[1], ARGV[i], ARGV[i+1]) end
            redis.call('HSETNX', KEYS[1], 'gen', ARGV[3])
            redis.call('HSETNX', KEYS[1], 'count', ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final DailyStockRepository dailyStockRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationProperties properties;
    private final Clock clock;

    /**
     * 검사·차감. 실패하면 아무것도 바꾸지 않고 예외: 하루 건수 초과 422, 잔여·1인 한도 부족 409 + errors[]
     *
     * @return 차감한 user_qty 의 세대. 보상할 때 release 에 넘긴다
     */
    public String reserve(ReservationPlan plan) {
        List<String> keys = keys(plan);
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(properties.dailyLimitPerMember()));
        for (ReservationPlan.Item item : plan.items()) {
            args.add(String.valueOf(item.breadId()));
            args.add(String.valueOf(item.quantity()));
            args.add(String.valueOf(item.perUserLimit() == null ? -1 : item.perUserLimit()));
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            List<String> result = strings(redisTemplate.execute(RESERVE, keys, args.toArray()));
            switch (result.get(0)) {
                case "OK" -> {
                    return result.get(1);
                }
                case "MISSING" -> load(plan, result.subList(1, result.size()));
                case "COUNT_EXCEEDED" -> throw new BusinessException(ErrorCode.RESERVATION_COUNT_EXCEEDED);
                default -> throw stockFailure(result.subList(1, result.size()));
            }
        }
        throw new IllegalStateException("Redis 재고 키를 채운 뒤에도 키가 없음 saleDate=" + plan.saleDate());
    }

    /**
     * 차감을 되돌린다. 재고 키가 없으면 건너뛰고, user_qty 는 세대가 같을 때만 뺀다.
     *
     * @param quantities breadId → 수량
     * @param generation 차감했던 user_qty 의 세대. null 이면 user_qty 는 건드리지 않는다
     */
    public void release(LocalDate saleDate, long memberId, Map<Long, Integer> quantities, String generation) {
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        args.add(generation == null ? "" : generation);
        quantities.forEach((breadId, quantity) -> {
            keys.add(StockKeys.stock(saleDate, breadId));
            args.add(String.valueOf(breadId));
            args.add(String.valueOf(quantity));
        });
        keys.add(StockKeys.userQty(saleDate, memberId));
        redisTemplate.execute(RELEASE, keys, args.toArray());
    }

    /** 지금 user_qty 의 세대. 적재 전이면 null. 취소가 커밋 전에 읽어 두고 커밋 후 release 에 넘긴다 */
    public String generation(LocalDate saleDate, long memberId) {
        Object generation = redisTemplate.opsForHash().get(StockKeys.userQty(saleDate, memberId), "gen");
        return generation == null ? null : generation.toString();
    }

    /** DB 기준으로 빠진 키만 채운다. NX 라 앱 여러 개가 동시에 채워도 먼저 쓴 값을 덮지 않는다 */
    private void load(ReservationPlan plan, List<String> missing) {
        LocalDate date = plan.saleDate();
        Duration ttl = StockKeys.ttl(date, LocalDateTime.now(clock));
        List<Long> breadIds = missing.stream().filter(value -> !"USER".equals(value)).map(Long::valueOf).toList();
        if (!breadIds.isEmpty()) {
            for (BreadQuantity remaining : dailyStockRepository.findRemaining(date, breadIds)) {
                redisTemplate.opsForValue().setIfAbsent(StockKeys.stock(date, remaining.getBreadId()),
                        String.valueOf(remaining.getQuantity()), ttl);
            }
        }
        if (missing.contains("USER")) {
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(ttl.toSeconds()));
            args.add(String.valueOf(reservationRepository.countActive(plan.memberId(), date)));
            args.add(UUID.randomUUID().toString());
            for (BreadQuantity mine : dailyStockRepository.sumReservedQuantities(plan.memberId(), date)) {
                args.add(String.valueOf(mine.getBreadId()));
                args.add(String.valueOf(mine.getQuantity()));
            }
            redisTemplate.execute(LOAD_USER, List.of(StockKeys.userQty(date, plan.memberId())), args.toArray());
        }
    }

    private static BusinessException stockFailure(List<String> fails) {
        List<StockFailure> errors = fails.stream().map(fail -> {
            String[] parts = fail.split(":");
            return new StockFailure(Long.parseLong(parts[0]), parts[1], Integer.parseInt(parts[2]));
        }).toList();
        return outOfStock(errors);
    }

    /** 잔여 부족이 하나라도 있으면 OUT_OF_STOCK, 1인 한도만 넘었으면 PER_USER_LIMIT_EXCEEDED. errors[]에는 빵별 사유 전부 */
    static BusinessException outOfStock(List<StockFailure> errors) {
        boolean outOfStock = errors.stream().anyMatch(error -> error.reason().equals("OUT_OF_STOCK"));
        return new BusinessException(outOfStock ? ErrorCode.OUT_OF_STOCK : ErrorCode.PER_USER_LIMIT_EXCEEDED, null,
                Map.of("errors", errors));
    }

    private static List<String> keys(ReservationPlan plan) {
        List<String> keys = new ArrayList<>();
        plan.items().forEach(item -> keys.add(StockKeys.stock(plan.saleDate(), item.breadId())));
        keys.add(StockKeys.userQty(plan.saleDate(), plan.memberId()));
        return keys;
    }

    private static List<String> strings(List<?> values) {
        return values.stream().map(String::valueOf).toList();
    }
}
