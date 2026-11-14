package com.fivexbread.batch;

import com.fivexbread.common.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 폴러와 정리 (8장 OutboxRelay, decisions.md 6장 "아웃박스 흐름"). 앱 3개 모두 같은 초에 깨어나고 락을 잡은 1개만 돈다.
 * <ul>
 *   <li>relay: 5초마다. lockAtMostFor 30초는 1회 상한(100행 × 10묶음, 행당 수 ms)보다 넉넉하고, 실행 중 앱이 죽어도
 *       다음 실행이 30초 넘게 막히지 않을 만큼 짧다. 넘겨서 두 앱이 겹쳐도 applied 키로 한 번만 적용된다.
 *       lockAtLeastFor 는 두지 않는다. 주기가 짧아 오래 잡으면 다음 주기를 건너뛴다</li>
 *   <li>cleanup: 하루 1번. NoShowJob 과 같은 이유로 lockAtLeastFor 30초</li>
 * </ul>
 * 락은 이 메서드를 호출할 때 걸린다(프록시). 락 없이 처리만 하려면 OutboxRelay 를 부른다.
 */
@Component
@RequiredArgsConstructor
public class OutboxRelayJob {

    static final String RELAY_LOCK = "outboxRelay";
    static final String CLEANUP_LOCK = "outboxCleanup";

    private final OutboxRelay outboxRelay;

    @Scheduled(cron = "${fivexbread.outbox.relay-cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = RELAY_LOCK, lockAtMostFor = "PT30S")
    public void relay() {
        outboxRelay.publishPending();
    }

    @Scheduled(cron = "${fivexbread.outbox.cleanup-cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = CLEANUP_LOCK, lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void cleanup() {
        outboxRelay.deletePublished();
    }
}
