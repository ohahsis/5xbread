package com.fivexbread.batch;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fivexbread.saledate.StockReconciler;
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

/** 정합성 점검이 실제 Redis ShedLock 으로 한 번만 실행되는지 본다. 처리 내용은 StockReconcilerTest 가 보고, 여기서는 호출 수만 센다 */
class StockReconcileJobLockTest extends IntegrationTestSupport {

    private static final String LOCK_KEY = "job-lock:default:" + StockReconcileJob.LOCK_NAME;

    @MockitoBean
    StockReconciler stockReconciler;

    @Autowired
    StockReconcileJob job;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void releaseLock() {
        redis.delete(LOCK_KEY);
    }

    @Test
    void 두_스레드가_동시에_호출하면_한쪽만_실행하고_lockAtLeastFor_동안은_다시_실행하지_않는다() throws Exception {
        Callable<Void> call = () -> {
            job.run();
            return null;
        };

        Concurrently.run(List.of(call, call), 2);
        job.run();

        verify(stockReconciler, times(1)).runScheduled();
    }
}
