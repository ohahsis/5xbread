package com.fivexbread.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fivexbread.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** application.yml 의 설정 값이 실제 빈에 들어갔는지 본다 (decisions.md 6장) */
class ConfigWiringTest extends IntegrationTestSupport {

    @Autowired
    @Qualifier("taskScheduler")
    ThreadPoolTaskScheduler taskScheduler;

    @Test
    void 스케줄러는_spring_task_scheduling_pool_size_4를_builder로_받는다() {
        // getPoolSize() 는 지금 떠 있는 스레드 수라 설정 값은 core pool size 로 본다
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
        assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
    }
}
