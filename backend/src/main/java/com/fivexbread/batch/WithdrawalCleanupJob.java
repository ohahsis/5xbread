package com.fivexbread.batch;

import com.fivexbread.member.WithdrawalProperties;
import com.fivexbread.member.WithdrawalService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재가입 제한 기간(기본 30일)이 지난 탈퇴 번호 해시 정리 (AU-07, 13-5 "필요한 기간만 가지고 30일이 지나면 배치로 삭제").
 * 하루 1번, 앱 3개 중 1개만(ShedLock). 다시 돌려도 결과가 같아 겹쳐 돌아도 문제없다. OutboxRelayJob.cleanup 과 같은 락 시간.
 * 가입 검사는 시각으로 비교하므로(MemberService.checkRejoin) 정리가 늦어도 제한이 길어지지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawalCleanupJob {

    static final String LOCK = "withdrawalCleanup";

    private final WithdrawalService withdrawalService;
    private final WithdrawalProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${fivexbread.withdrawal.cleanup-cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void clearExpiredPhoneHashes() {
        int cleared = withdrawalService.clearExpiredPhoneHashes(LocalDateTime.now(clock).minus(properties.rejoinAfter()));
        if (cleared > 0) {
            log.info("재가입 제한이 끝난 탈퇴 번호 해시 정리 cleared={}", cleared);
        }
    }
}
