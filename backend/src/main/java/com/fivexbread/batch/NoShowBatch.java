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
 *
 * 대상은 오늘과, 최근 CATCH_UP_DAYS 일 안에서 아직 RESERVED 가 남은 지난 판매일이다(decisions.md 6장 "A-07 이어받기").
 * 그날 21:00 에 앱이 모두 내려가 있었거나 처리 중 죽었으면 다음 실행이 이어서 처리한다. R-17 은 판매일 기준으로 센다.
 * 이어받는 기간(7일)이 제한 기간(14일)보다 짧아, 늦게 처리해도 제한 끝(판매일 + 14일)은 항상 오늘 이후다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoShowBatch {

    static final int CHUNK_SIZE = 1_000;
    static final int CATCH_UP_DAYS = 7;

    private final NoShowService noShowService;
    private final Clock clock;

    public void run() {
        LocalDate today = LocalDate.now(clock);
        List<LocalDate> missed = noShowService.saleDatesWithReserved(today.minusDays(CATCH_UP_DAYS), today.minusDays(1));
        if (!missed.isEmpty()) {
            log.warn("노쇼 처리를 못 한 지난 판매일을 이어서 처리 saleDates={}", missed);
        }
        for (LocalDate saleDate : missed) {
            process(saleDate);
        }
        process(today);
    }

    private void process(LocalDate saleDate) {
        long startedAt = System.nanoTime();
        log.info("노쇼 처리 시작 saleDate={}", saleDate);

        int noShows = markNoShows(saleDate);
        List<Long> memberIds = noShowService.noShowMemberIds(saleDate);
        int blocked = applyBlocks(memberIds, saleDate);

        log.info("노쇼 처리 완료 saleDate={} noShow={} noShowMembers={} blocked={} elapsedMs={}", saleDate, noShows,
                memberIds.size(), blocked, (System.nanoTime() - startedAt) / 1_000_000);
    }

    // OFFSET 을 쓰지 않는다. 처리한 행은 RESERVED 에서 빠지므로 매번 앞의 묶음을 읽으면 된다 (13-10 꼬리 질문)
    private int markNoShows(LocalDate saleDate) {
        int total = 0;
        Chunk chunk;
        do {
            chunk = noShowService.markChunk(saleDate, CHUNK_SIZE);
            total += chunk.marked();
        } while (chunk.candidates() > 0);
        return total;
    }

    private int applyBlocks(List<Long> memberIds, LocalDate saleDate) {
        int blocked = 0;
        for (int from = 0; from < memberIds.size(); from += CHUNK_SIZE) {
            int to = Math.min(from + CHUNK_SIZE, memberIds.size());
            blocked += noShowService.applyBlocks(memberIds.subList(from, to), saleDate);
        }
        return blocked;
    }
}
