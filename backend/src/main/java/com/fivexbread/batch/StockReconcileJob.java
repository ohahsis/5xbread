package com.fivexbread.batch;

import com.fivexbread.saledate.StockReconciler;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정합성 점검 스케줄 (N-05, 13-10: 5분마다). 앱 3개가 같은 시각에 깨어나고 락을 잡은 1개만 돈다.
 * <ul>
 *   <li>lockAtMostFor 4분: 주기(5분)보다 짧아 실행 중 앱이 죽어도 다음 회차는 막히지 않는다. 1회는 판매일 수 × 빵 수만큼의
 *       MGET·조회라 수십 ms 다</li>
 *   <li>lockAtLeastFor 30초: NoShowJob 과 같은 이유(시계가 조금 늦은 앱이 같은 회차를 한 번 더 돌지 않게). 그래도 겹치면
 *       StockReconciler 가 minGap 보다 가까운 관측을 2회로 세지 않는다</li>
 * </ul>
 * 락은 이 메서드를 호출할 때 걸린다(프록시). 락 없이 처리만 하려면 StockReconciler 를 부른다.
 */
@Component
@RequiredArgsConstructor
public class StockReconcileJob {

    static final String LOCK_NAME = "stockReconcileJob";

    private final StockReconciler stockReconciler;

    @Scheduled(cron = "${fivexbread.reconcile.cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void run() {
        stockReconciler.runScheduled();
    }
}
