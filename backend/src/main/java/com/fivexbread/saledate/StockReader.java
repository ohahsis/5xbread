package com.fivexbread.saledate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 빵 목록의 Redis 읽기 (13-4 4·5단계, 13-3 2단계 스냅샷). 버전·잔여·내 수량을 Lua 하나로 읽어 왕복 1번이다.
 * 버전과 잔여를 한 스크립트에서 읽어야 스냅샷이 정확히 그 버전의 상태가 된다. 따로 읽으면 사이에 끼어든 변경이
 * 잔여에는 들어가고 버전에는 빠지거나(또는 그 반대) 해서, 화면이 "목록 버전보다 큰 메시지만 적용"할 때 어긋난다.
 */
@Component
@RequiredArgsConstructor
public class StockReader {

    private static final String LOADED_FIELD = "count";     // count 가 있어야 적재한 해시다 (StockKeys.userQty)
    private static final String MISSING = "";               // Lua 배열에 nil 을 넣으면 배열이 거기서 끊기므로 빈 문자열로 표시

    /*
     * KEYS[1]: 버전, KEYS[2..n+1]: 재고 키, KEYS[n+2]: user_qty(로그인일 때만) / ARGV[1]: n
     * 반환: {버전(없으면 '0'), 잔여 n개(없으면 ''), user_qty 의 HGETALL 필드·값...}
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> READ = RedisScript.of("""
            local n = tonumber(ARGV[1])
            local out = {redis.call('GET', KEYS[1]) or '0'}
            for i = 1, n do out[#out+1] = redis.call('GET', KEYS[i+1]) or '' end
            if #KEYS > n + 1 then
              for _, value in ipairs(redis.call('HGETALL', KEYS[n+2])) do out[#out+1] = value end
            end
            return out
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * @param version   판매일 잔여 버전(stockver). 없으면 0
     * @param remaining 키가 있는 빵의 잔여. 없는 빵은 빠진다
     * @param mine      빵별 내 예약 수량. 해시가 적재되지 않았으면 null
     */
    public record Snapshot(long version, Map<Long, Integer> remaining, Map<Long, Integer> mine) {

        public static final Snapshot EMPTY = new Snapshot(0, Map.of(), null);
    }

    /** @param memberId null 이면 비로그인: 해시를 읽지 않는다 */
    public Snapshot read(LocalDate saleDate, List<Long> breadIds, Long memberId) {
        List<String> keys = new ArrayList<>();
        keys.add(StockKeys.version(saleDate));
        breadIds.forEach(id -> keys.add(StockKeys.stock(saleDate, id)));
        if (memberId != null) {
            keys.add(StockKeys.userQty(saleDate, memberId));
        }
        List<?> result = redisTemplate.execute(READ, keys, String.valueOf(breadIds.size()));

        Map<Long, Integer> remaining = new HashMap<>();
        for (int i = 0; i < breadIds.size(); i++) {
            String value = String.valueOf(result.get(i + 1));
            if (!MISSING.equals(value)) {
                remaining.put(breadIds.get(i), Integer.valueOf(value));
            }
        }
        Map<String, String> hash = new HashMap<>();
        for (int i = breadIds.size() + 1; i + 1 < result.size(); i += 2) {
            hash.put(String.valueOf(result.get(i)), String.valueOf(result.get(i + 1)));
        }
        long version = Long.parseLong(String.valueOf(result.get(0)));
        return new Snapshot(version, remaining, memberId == null ? null : mine(breadIds, hash));
    }

    // 빵 id 로만 꺼내므로 count·gen 필드는 응답에 나가지 않는다
    private static Map<Long, Integer> mine(List<Long> breadIds, Map<String, String> hash) {
        if (!hash.containsKey(LOADED_FIELD)) {
            return null;
        }
        Map<Long, Integer> mine = new HashMap<>();
        for (Long breadId : breadIds) {
            String quantity = hash.get(String.valueOf(breadId));
            if (quantity != null) {
                mine.put(breadId, Integer.valueOf(quantity));
            }
        }
        return mine;
    }
}
