package com.fivexbread.saledate;

import static com.fivexbread.saledate.StockRowError.Reason.STOCK_BELOW_RESERVED;

import com.fivexbread.saledate.StockService.ReservableChange;
import com.fivexbread.saledate.StockService.Result;
import com.fivexbread.saledate.StockRedisSync.DecreaseResult;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 수량 저장과 Redis 잔여 반영의 순서 (decisions.md 6장 "A-02 Redis 반영"). 트랜잭션 안에서는 Redis 를 부르지 않는다.
 *
 * 1. 미리 검사(404·422·412)하고 기존 행의 총량 변화를 구한다
 * 2. 줄이는 빵: Redis Lua 로 잔여가 줄일 양 이상일 때만 한꺼번에 DECRBY. 모자라면 422, DB 는 건드리지 않는다
 * 3. DB 트랜잭션(StockService.put). 실패하면 2에서 줄인 만큼 Redis 에 되돌린다
 * 4. 커밋 후: 늘리는 빵만 INCRBY (afterCommit)
 * 재고 키가 없는 빵은 Redis 를 건너뛴다. 첫 예약 때 DB 기준으로 적재된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockFacade {

    private final StockService stockService;
    private final StockRedisSync stockRedisSync;

    public Result put(LocalDate date, int expectedVersion, List<StockRowRequest> rows) {
        List<ReservableChange> changes = stockService.reservableChanges(date, expectedVersion, rows);
        Map<Long, Integer> decreases = amounts(changes, false);
        Map<Long, Integer> increases = amounts(changes, true);

        DecreaseResult decreased = stockRedisSync.decrease(date, decreases);
        if (decreased.failed()) {
            // Redis 잔여가 줄일 양보다 적다 = 처리 중인 예약까지 합치면 새 총량이 예약 수량보다 작다 (R-07)
            Map<Long, Integer> before = new LinkedHashMap<>();
            changes.forEach(change -> before.put(change.breadId(), change.before()));
            throw StockService.invalidRows(decreased.shortages().entrySet().stream()
                    .map(e -> new StockRowError(e.getKey(), "reservableQty", STOCK_BELOW_RESERVED,
                            before.get(e.getKey()) - e.getValue()))
                    .toList());
        }

        Result result;
        try {
            result = stockService.put(date, expectedVersion, rows);
        } catch (RuntimeException e) {
            restore(date, decreases, decreased.decreased());
            throw e;
        }
        afterCommit(date, increases);
        return result;
    }

    /**
     * 커밋 후 처리를 모으는 곳. 3단계에서 판매일 캐시 무효화·잔여 변경 발행(C-03)을 여기에 붙인다.
     * 늘리기는 실패해도 저장은 이미 끝났다. Redis 잔여가 DB 보다 적은 "덜 팔리는" 방향이라 ERROR 로그만 남긴다 (N-05 가 보정).
     */
    private void afterCommit(LocalDate date, Map<Long, Integer> increases) {
        try {
            stockRedisSync.increase(date, increases);
        } catch (RuntimeException e) {
            log.error("수량 늘리기 Redis 반영 실패. Redis 잔여가 DB 보다 적음 saleDate={} increases={}", date, increases, e);
        }
    }

    private void restore(LocalDate date, Map<Long, Integer> decreases, List<Long> decreasedBreadIds) {
        Map<Long, Integer> amounts = new LinkedHashMap<>();
        decreasedBreadIds.forEach(breadId -> amounts.put(breadId, decreases.get(breadId)));
        try {
            stockRedisSync.increase(date, amounts);
        } catch (RuntimeException e) {
            log.error("수량 줄이기 Redis 보상 실패. Redis 잔여가 DB 보다 적음 saleDate={} amounts={}", date, amounts, e);
        }
    }

    // 늘리는 빵(increase=true) 또는 줄이는 빵의 양(양수)
    private static Map<Long, Integer> amounts(List<ReservableChange> changes, boolean increase) {
        Map<Long, Integer> amounts = new LinkedHashMap<>();
        changes.stream()
                .filter(change -> increase == change.delta() > 0)
                .forEach(change -> amounts.put(change.breadId(), Math.abs(change.delta())));
        return amounts;
    }
}
