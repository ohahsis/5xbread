package com.fivexbread.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * decisions.md 3장: 현재 시각은 주입한 Clock(Asia/Seoul)만 사용. LocalDateTime.now() 직접 호출 금지.
 * 테스트는 이 빈을 Clock.fixed(...)로 바꿔 시각을 고정한다.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
