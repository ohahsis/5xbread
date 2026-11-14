package com.fivexbread.saledate;

import static com.fivexbread.common.ClockConfig.KST;

import com.fivexbread.admin.AdminAction;
import com.fivexbread.admin.AdminActionLog;
import com.fivexbread.admin.AdminActionLogRepository;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.saledate.StockReconciler.Observation;
import com.fivexbread.saledate.StockReconciliationResponse.Item;
import com.fivexbread.saledate.StockReconciliationResponse.Result;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.stereotype.Component;

/**
 * 수량 재계산 (A-12, 13-10 동작 흐름). 사장님이 누르면 정기 점검의 "5분 간격 연속 2회" 없이 바로 보정한다.
 * 대신 manualConfirmDelay(1초) 간격으로 두 번 관측해 차이가 같은 빵만 고친다. 진행 중인 예약(Redis 차감 → DB 커밋)은
 * 수십 ms 안에 끝나므로 1초 뒤에도 같은 차이면 남은 어긋남으로 본다 (decisions.md 6장 "A-12 진행 중 예약과의 경합").
 * 트랜잭션 없이 Redis 를 다루고, 마지막에 재계산 기록(admin_action_log)만 저장한다.
 *
 * 1. 판매일 없음 404, 휴무일 422 → 2. 판매일 락(정기 점검과 공유, 못 잡으면 409) → 3. 관측 → 1초 → 관측
 * 4. 빵별 판정·보정, 키가 없으면 예약이 끝나지 않은 판매일만 DB 기준으로 적재 → 5. 기록 저장 → 201
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReconciliationFacade {

    private final SaleDateRepository saleDateRepository;
    private final StockReconciler stockReconciler;
    private final StockRedisSync stockRedisSync;
    private final AdminActionLogRepository adminActionLogRepository;
    private final ReconcileProperties properties;
    private final Clock clock;

    public StockReconciliationResponse reconcile(LocalDate date, long adminId) {
        SaleDate saleDate = saleDateRepository.findById(date).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (saleDate.isHoliday()) {
            throw new BusinessException(ErrorCode.SALE_DATE_HOLIDAY);
        }
        boolean reservable = switch (saleDate.status(LocalDateTime.now(clock))) {
            case NOT_OPEN, OPEN -> true;
            default -> false;
        };
        SimpleLock lock = stockReconciler.tryLock(date)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECONCILIATION_IN_PROGRESS));
        List<Item> items = new ArrayList<>();
        try {
            Map<Long, Observation> first = stockReconciler.observe(date);
            sleep();
            Map<Long, Observation> second = stockReconciler.observe(date);
            Map<Long, Integer> toLoad = new LinkedHashMap<>();
            for (Observation observation : second.values()) {
                items.add(judge(date, first.get(observation.breadId()), observation, reservable, toLoad));
            }
            stockRedisSync.fillMissing(date, toLoad);                 // DB 를 읽은 뒤라 적재 시각 규칙을 지킨다
            stockReconciler.clearSeen(date, second.keySet());
        } finally {
            lock.unlock();
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);    // 기록(DATETIME(3))과 같게
        AdminActionLog record = adminActionLogRepository.save(AdminActionLog.onSaleDate(adminId,
                AdminAction.STOCK_RECONCILED, date, Map.of("breads", items), now));
        return new StockReconciliationResponse(record.getId(), date, now.atZone(KST).toOffsetDateTime(), items);
    }

    private Item judge(LocalDate date, Observation first, Observation second, boolean reservable,
                       Map<Long, Integer> toLoad) {
        long breadId = second.breadId();
        if (second.redis() == null) {
            if (!reservable) {
                return new Item(breadId, null, second.db(), 0, Result.NO_KEY);
            }
            toLoad.put(breadId, second.db());
            return new Item(breadId, null, second.db(), 0, Result.LOADED);
        }
        int delta = second.delta();
        if (delta == 0) {
            return new Item(breadId, second.redis(), second.db(), 0, Result.MATCHED);
        }
        if (first == null || first.delta() == null || first.delta() != delta) {
            return new Item(breadId, second.redis(), second.db(), 0, Result.CHANGING);
        }
        Long after = stockRedisSync.adjust(date, breadId, delta);
        if (after == null) {                                          // 관측 뒤 키가 사라짐(만료·재시작)
            return new Item(breadId, null, second.db(), 0, Result.NO_KEY);
        }
        log.warn("수량 재계산: Redis 잔여 보정 saleDate={} breadId={} redis={} db={} adjustment={} after={}",
                date, breadId, second.redis(), second.db(), delta, after);
        return new Item(breadId, second.redis(), second.db(), delta, Result.ADJUSTED);
    }

    private void sleep() {
        try {
            Thread.sleep(properties.manualConfirmDelay().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수량 재계산 대기 중 중단됨", e);
        }
    }
}
