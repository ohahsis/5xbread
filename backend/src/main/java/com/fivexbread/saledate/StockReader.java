package com.fivexbread.saledate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 빵 목록의 Redis 읽기 (13-4 4·5단계). MGET 과 HGETALL 을 파이프라인으로 묶어 왕복 1번이다 */
@Component
@RequiredArgsConstructor
public class StockReader {

    private static final String LOADED_FIELD = "count";     // count 가 있어야 적재한 해시다 (StockKeys.userQty)

    private final StringRedisTemplate redisTemplate;

    /**
     * @param remaining 키가 있는 빵의 잔여. 없는 빵은 빠진다
     * @param mine      빵별 내 예약 수량. 해시가 적재되지 않았으면 null
     */
    public record Snapshot(Map<Long, Integer> remaining, Map<Long, Integer> mine) {

        public static final Snapshot EMPTY = new Snapshot(Map.of(), null);
    }

    /** @param memberId null 이면 비로그인: 해시를 읽지 않는다 */
    @SuppressWarnings("unchecked")
    public Snapshot read(LocalDate saleDate, List<Long> breadIds, Long memberId) {
        byte[][] stockKeys = breadIds.stream().map(id -> bytes(StockKeys.stock(saleDate, id))).toArray(byte[][]::new);
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            connection.stringCommands().mGet(stockKeys);
            if (memberId != null) {
                connection.hashCommands().hGetAll(bytes(StockKeys.userQty(saleDate, memberId)));
            }
            return null;
        });

        List<String> stocks = (List<String>) results.get(0);
        Map<Long, Integer> remaining = new HashMap<>();
        for (int i = 0; i < breadIds.size(); i++) {
            if (stocks.get(i) != null) {
                remaining.put(breadIds.get(i), Integer.valueOf(stocks.get(i)));
            }
        }
        return new Snapshot(remaining, memberId == null ? null : mine(breadIds, (Map<String, String>) results.get(1)));
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

    private static byte[] bytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }
}
