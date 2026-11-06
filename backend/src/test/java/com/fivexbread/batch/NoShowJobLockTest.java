package com.fivexbread.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fivexbread.support.Concurrently;
import com.fivexbread.support.IntegrationTestSupport;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** ShedLock 이 실제 Redis 로 한 번만 실행시키는지 본다 (13-10). 처리 내용은 NoShowBatchTest 가 보고, 여기서는 호출 수만 센다 */
class NoShowJobLockTest extends IntegrationTestSupport {

    private static final String LOCK_KEY = "job-lock:default:" + NoShowJob.LOCK_NAME;

    @MockitoBean
    NoShowBatch noShowBatch;

    @Autowired
    NoShowJob noShowJob;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void releaseLock() {
        redis.delete(LOCK_KEY);
    }

    @Test
    void 두_스레드가_동시에_호출하면_한쪽만_실행한다() throws Exception {
        Callable<Void> call = () -> {
            noShowJob.run();
            return null;
        };

        Concurrently.run(List.of(call, call), 2);

        verify(noShowBatch, times(1)).run();
    }

    @Test
    void 끝나도_lockAtLeastFor_동안은_다시_실행하지_않고_락이_없어지면_실행한다() {
        noShowJob.run();

        assertThat(redis.getExpire(LOCK_KEY, TimeUnit.SECONDS)).isBetween(1L, 30L);
        noShowJob.run();
        verify(noShowBatch, times(1)).run();

        redis.delete(LOCK_KEY);
        noShowJob.run();
        verify(noShowBatch, times(2)).run();
    }
}
