package com.fivexbread.batch;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fivexbread.common.outbox.OutboxRelay;
import com.fivexbread.support.Concurrently;
import com.fivexbread.support.IntegrationTestSupport;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 아웃박스 폴러가 실제 Redis ShedLock 으로 겹쳐 돌지 않는지 본다. 처리 내용은 OutboxRelayTest 가 보고, 여기서는 호출 수만 센다.
 * 폴러에는 lockAtLeastFor 가 없어 끝나면 바로 풀리므로, 1회 실행이 0.5초 걸리게 해 두 호출이 확실히 겹치게 한다.
 */
class OutboxRelayJobLockTest extends IntegrationTestSupport {

    private static final String LOCK_KEY = "job-lock:default:" + OutboxRelayJob.RELAY_LOCK;

    @MockitoBean
    OutboxRelay outboxRelay;

    @Autowired
    OutboxRelayJob outboxRelayJob;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void releaseLock() {
        redis.delete(LOCK_KEY);
    }

    @Test
    void 두_스레드가_동시에_호출하면_한쪽만_실행하고_끝나면_바로_다시_실행할_수_있다() throws Exception {
        doAnswer(invocation -> {
            Thread.sleep(500);
            return 0;
        }).when(outboxRelay).publishPending();
        Callable<Void> call = () -> {
            outboxRelayJob.relay();
            return null;
        };

        Concurrently.run(List.of(call, call), 2);
        verify(outboxRelay, times(1)).publishPending();

        outboxRelayJob.relay();                                    // lockAtLeastFor 가 없어 다음 주기를 막지 않는다
        verify(outboxRelay, times(2)).publishPending();
    }
}
