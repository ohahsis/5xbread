package com.fivexbread.batch;

import com.fivexbread.reservation.NoShowService;
import com.fivexbread.reservation.NoShowService.Chunk;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 노쇼 처리 순서 (13-10 동작 흐름). 묶음마다 트랜잭션이 따로라, 여기에는 @Transactional 을 두지 않는다.
 * 어느 지점에서 죽어도 다시 돌리면 남은 것만 처리한다: 끝난 묶음은 RESERVED 가 아니고, R-17 대상은 DB 로 다시 구한다.
 * 재고는 되돌리지 않고 Redis 도 부르지 않는다 (decisions.md 6장 "A-07 재고").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoShowBatch {

    static final int CHUNK_SIZE = 1_000;

    private final NoShowService noShowService;
    private final Clock clock;

    public void run() {
        long startedAt = System.nanoTime();
        LocalDate today = LocalDate.now(clock);
        log.info("노쇼 처리 시작 saleDate={}", today);

        int noShows = markNoShows(today);
        List<Long> memberIds = noShowService.noShowMemberIds(today);
        int blocked = applyBlocks(memberIds, today);

        log.info("노쇼 처리 완료 saleDate={} noShow={} noShowMembers={} blocked={} elapsedMs={}", today, noShows,
                memberIds.size(), blocked, (System.nanoTime() - startedAt) / 1_000_000);
    }

    // OFFSET 을 쓰지 않는다. 처리한 행은 RESERVED 에서 빠지므로 매번 앞의 묶음을 읽으면 된다 (13-10 꼬리 질문)
    private int markNoShows(LocalDate today) {
        int total = 0;
        Chunk chunk;
        do {
            chunk = noShowService.markChunk(today, CHUNK_SIZE);
            total += chunk.marked();
        } while (chunk.candidates() > 0);
        return total;
    }

    private int applyBlocks(List<Long> memberIds, LocalDate today) {
        int blocked = 0;
        for (int from = 0; from < memberIds.size(); from += CHUNK_SIZE) {
            int to = Math.min(from + CHUNK_SIZE, memberIds.size());
            blocked += noShowService.applyBlocks(memberIds.subList(from, to), today);
        }
        return blocked;
    }
}
