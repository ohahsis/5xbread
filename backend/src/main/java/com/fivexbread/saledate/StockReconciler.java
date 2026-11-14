package com.fivexbread.saledate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 잔여와 DB 기준 잔여(reservable_qty − reserved_qty) 비교·보정 (N-05, 13-10). 기준은 DB 다.
 * 트랜잭션 없이 돈다(Redis 를 부르므로). 정기 점검(StockReconcileJob)과 사장님 재계산(A-12)이 같은 관측·보정·락을 쓴다.
 * <ul>
 *   <li>관측: DB 를 먼저 읽고 Redis 를 MGET. 재고 키가 없는 빵은 비교하지 않는다(다음 예약·조회 때 DB 기준으로 적재)</li>
 *   <li>보정: SET 이 아니라 차이만 INCRBY, 키 확인과 같은 Lua 에서 (StockRedisSync.adjust)</li>
 *   <li>락: 판매일별 "stockReconcile:{날짜}". 점검과 재계산이 같은 차이를 동시에 두 번 더하지 않게 한다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReconciler {

    private static final Duration DATE_LOCK_AT_MOST_FOR = Duration.ofMinutes(1);

    private final SaleDateRepository saleDateRepository;
    private final DailyStockRepository dailyStockRepository;
    private final StockRedisSync stockRedisSync;
    private final StringRedisTemplate redisTemplate;
    private final LockProvider lockProvider;
    private final ReconcileProperties properties;
    private final Clock clock;

    /** @param redis Redis 잔여, 키가 없으면 null / db DB 기준 잔여 */
    public record Observation(long breadId, Integer redis, int db) {

        /** DB − Redis. 양수면 Redis 가 적다(덜 팔림), 음수면 많다(DB 조건부 UPDATE 가 막지만 목록이 틀림) */
        public Integer delta() {
            return redis == null ? null : db - redis;
        }
    }

    /** 그날 수량 행 전부의 관측 (breadId 오름차순) */
    public Map<Long, Observation> observe(LocalDate date) {
        Map<Long, Integer> db = new LinkedHashMap<>();
        dailyStockRepository.findAllRemaining(date)
                .forEach(row -> db.put(row.getBreadId(), row.getQuantity().intValue()));
        if (db.isEmpty()) {
            return Map.of();
        }
        List<Long> breadIds = new ArrayList<>(db.keySet());
        List<String> values = redisTemplate.opsForValue()
                .multiGet(breadIds.stream().map(id -> StockKeys.stock(date, id)).toList());
        Map<Long, Observation> observations = new LinkedHashMap<>();
        for (int i = 0; i < breadIds.size(); i++) {
            long breadId = breadIds.get(i);
            String value = values == null ? null : values.get(i);
            observations.put(breadId, new Observation(breadId, value == null ? null : Integer.valueOf(value),
                    db.get(breadId)));
        }
        return observations;
    }

    /**
     * 판매일별 락. 잡지 못하면 비어 있다. 락 시각은 ShedLock 이 실제 시각으로 잰다(주입한 Clock 과 무관, SchedulerLockConfig 와 같음)
     */
    public Optional<SimpleLock> tryLock(LocalDate date) {
        return lockProvider.lock(new LockConfiguration(ClockProvider.now(), "stockReconcile:" + date,
                DATE_LOCK_AT_MOST_FOR, Duration.ZERO));
    }

    /** 보정 뒤 첫 관측이 남아 있으면 다음 회차가 옛 차이로 판단하므로 지운다 */
    public void clearSeen(LocalDate date, Collection<Long> breadIds) {
        redisTemplate.delete(breadIds.stream().map(id -> StockKeys.reconcileSeen(date, id)).toList());
    }

    /**
     * 정기 점검 1회 (5분마다). 예약이 끝나지 않은 판매일(NOT_OPEN·OPEN)의 빵별로, 같은 차이가 minGap 이상 떨어진 두 회차에서
     * 연속으로 보일 때만 보정한다. 진행 중인 예약(Redis 는 뺐고 DB 는 커밋 전)·취소 복구 때문에 잠깐 보이는 차이를 거르려는 것.
     *
     * @return 보정한 빵 수
     */
    public int runScheduled() {
        LocalDateTime now = LocalDateTime.now(clock);
        int adjusted = 0;
        for (LocalDate date : saleDateRepository.findReservableDates(now)) {
            Optional<SimpleLock> lock = tryLock(date);
            if (lock.isEmpty()) {
                log.info("정합성 점검: 같은 판매일의 재계산이 진행 중이라 이번 회차는 건너뜀 saleDate={}", date);
                continue;
            }
            try {
                adjusted += check(date, now);
            } finally {
                lock.get().unlock();
            }
        }
        return adjusted;
    }

    private int check(LocalDate date, LocalDateTime now) {
        long nowMillis = StockKeys.epochMillis(now);
        int adjusted = 0;
        List<Long> matched = new ArrayList<>();
        for (Observation observation : observe(date).values()) {
            Integer delta = observation.delta();
            if (delta == null || delta == 0) {
                matched.add(observation.breadId());                  // 키 없음·일치: 이전 관측을 지운다
                continue;
            }
            String seenKey = StockKeys.reconcileSeen(date, observation.breadId());
            Seen previous = Seen.parse(redisTemplate.opsForValue().get(seenKey));
            if (previous != null && previous.delta() == delta) {
                if (nowMillis - previous.observedAt() >= properties.minGap().toMillis()) {
                    Long after = stockRedisSync.adjust(date, observation.breadId(), delta);
                    log.warn("정합성 점검: Redis 잔여 보정 saleDate={} breadId={} redis={} db={} adjustment={} after={}",
                            date, observation.breadId(), observation.redis(), observation.db(), delta, after);
                    redisTemplate.delete(seenKey);
                    adjusted++;
                }
                continue;                                             // 너무 이른 재관측은 2회로 세지 않고 첫 관측을 유지
            }
            redisTemplate.opsForValue().set(seenKey, delta + ":" + nowMillis, properties.observationTtl());
            log.info("정합성 점검: 차이 관측(다음 회차에도 같으면 보정) saleDate={} breadId={} redis={} db={} delta={}",
                    date, observation.breadId(), observation.redis(), observation.db(), delta);
        }
        if (!matched.isEmpty()) {
            clearSeen(date, matched);
        }
        return adjusted;
    }

    private record Seen(int delta, long observedAt) {

        static Seen parse(String value) {
            if (value == null) {
                return null;
            }
            String[] parts = value.split(":");
            return new Seen(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
        }
    }
}
