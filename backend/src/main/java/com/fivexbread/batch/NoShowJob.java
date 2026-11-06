package com.fivexbread.batch;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 노쇼 스케줄 (A-07, 13-10). 시각은 설정 파일(fivexbread.no-show.cron), 앱 3개 중 락을 잡은 1개만 실행한다.
 * <ul>
 *   <li>lockAtMostFor 10분: 실행 중 앱이 죽어도 락이 풀리는 상한. 9,000건이 1.5초라 여유가 크다(decisions.md 6장 측정값).
 *       넘겨도 조건부 UPDATE 라 같은 행을 두 번 바꾸지 않는다</li>
 *   <li>lockAtLeastFor 30초: 대상이 없으면 수 밀리초에 끝난다. 그때 바로 풀면 시계가 조금 늦은 앱이 뒤늦게 락을 잡아
 *       한 번 더 실행한다. 앱 사이 시계 차이보다 충분히 길게 둔다</li>
 * </ul>
 * 락은 이 메서드를 호출할 때 걸린다(프록시). 락 없이 처리만 하려면 NoShowBatch 를 부른다.
 */
@Component
@RequiredArgsConstructor
public class NoShowJob {

    static final String LOCK_NAME = "noShowJob";

    private final NoShowBatch noShowBatch;

    @Scheduled(cron = "${fivexbread.no-show.cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        noShowBatch.run();
    }
}
