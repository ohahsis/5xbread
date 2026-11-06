package com.fivexbread.common;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 앱 3개 중 1개만 실행하는 스케줄러 공통 설정 (decisions.md 6장 "ShedLock 공통 설정"). 아웃박스 폴러·정합성 점검도 이 설정을 쓴다.
 * 작업마다 @SchedulerLock 에 이름과 시간을 직접 적는다. 키는 Redis job-lock:default:{이름}.
 * 기본값에 lockAtLeastFor 를 두지 않는 이유: 몇 초마다 도는 폴러가 그 시간만큼 멈춘다.
 * 스케줄러 시각은 서버 시계를 따르므로 주입한 Clock 과 무관하고, 락 시간도 ShedLock 이 실제 시각으로 잰다.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory);
    }
}
