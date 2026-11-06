package com.fivexbread.saledate;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.saledate.SaleBreadsResponse.SaleBreadResponse;
import com.fivexbread.saledate.StockReader.Snapshot;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Component;

/**
 * 손님 빵 목록 (C-02, 13-4). 트랜잭션 없이 로컬 캐시 → Redis → (빠진 값만) DB 순서로 읽는다.
 * 평소 요청은 DB 0번, Redis 왕복 1번이다.
 * - 재고 키가 없는 빵: DB 값으로 응답하고, 예약 전 판매일이면 키를 채워 다음 요청부터 DB 로 가지 않게 한다
 * - Redis 장애: 공개 조회라 503 대신 DB 값으로 응답한다 (예약은 503, Q-05)
 * decisions.md 6장 "C-02 잔여 읽기".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaleBreadsFacade {

    // 매진은 아래로. 캐시 목록이 이미 노출 순서이고 정렬이 안정적이라 그 안의 순서는 유지된다 (13-4 6단계)
    private static final Comparator<SaleBreadResponse> SOLD_OUT_LAST = Comparator.comparing(bread -> bread.remaining() <= 0);

    private final SaleCatalogCache catalogCache;
    private final StockReader stockReader;
    private final StockRedisSync stockRedisSync;
    private final SaleDateQueryService queryService;
    private final Clock clock;
    private final AtomicBoolean redisDown = new AtomicBoolean();

    /** memberId 가 null 이면 비로그인: 내 예약 수량을 조회하지 않고 null 로 둔다 */
    public SaleBreadsResponse breads(LocalDate date, Long memberId) {
        SaleDateStatus status = catalogCache.schedule(date)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND))
                .status(LocalDateTime.now(clock));
        List<SaleBreadRow> rows = catalogCache.breads(date);
        if (rows.isEmpty()) {
            return new SaleBreadsResponse(date, status, List.of());
        }

        List<Long> breadIds = rows.stream().map(SaleBreadRow::breadId).toList();
        Snapshot snapshot = read(date, breadIds, memberId);
        Map<Long, Integer> remaining = withDbFallback(date, status, breadIds, snapshot.remaining());
        Map<Long, Integer> mine = memberId == null ? Map.of()
                : snapshot.mine() != null ? snapshot.mine() : queryService.myQuantities(date, memberId);

        List<SaleBreadResponse> breads = rows.stream()
                .map(row -> {
                    int left = remaining.getOrDefault(row.breadId(), 0);
                    return new SaleBreadResponse(row.breadId(), row.name(), row.price(), left, row.perUserLimit(),
                            memberId == null ? null : mine.getOrDefault(row.breadId(), 0),
                            SaleBreadStatus.of(status, left));
                })
                .sorted(SOLD_OUT_LAST)
                .toList();
        return new SaleBreadsResponse(date, status, breads);
    }

    private Snapshot read(LocalDate date, List<Long> breadIds, Long memberId) {
        try {
            Snapshot snapshot = stockReader.read(date, breadIds, memberId);
            if (redisDown.compareAndSet(true, false)) {
                log.info("Redis 복구. 빵 목록을 Redis 기준으로 되돌림");
            }
            return snapshot;
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            if (redisDown.compareAndSet(false, true)) {         // 요청마다 남기면 초당 수천 줄이 된다
                log.warn("Redis 장애. 복구될 때까지 빵 목록을 DB 기준으로 응답함", e);
            }
            return Snapshot.EMPTY;
        }
    }

    private Map<Long, Integer> withDbFallback(LocalDate date, SaleDateStatus status, List<Long> breadIds,
                                              Map<Long, Integer> fromRedis) {
        List<Long> missing = breadIds.stream().filter(id -> !fromRedis.containsKey(id)).toList();
        if (missing.isEmpty()) {
            return fromRedis;
        }
        Map<Long, Integer> fromDb = queryService.remaining(date, missing);
        if (!redisDown.get() && (status == SaleDateStatus.NOT_OPEN || status == SaleDateStatus.OPEN)) {
            fill(date, fromDb);
        }
        Map<Long, Integer> merged = new HashMap<>(fromRedis);
        merged.putAll(fromDb);
        return merged;
    }

    private void fill(LocalDate date, Map<Long, Integer> remaining) {
        try {
            stockRedisSync.fillMissing(date, remaining);
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            log.debug("재고 키 적재 실패. 다음 요청이 다시 시도함 saleDate={}", date);    // 응답은 이미 DB 값으로 만들 수 있다
        }
    }
}
