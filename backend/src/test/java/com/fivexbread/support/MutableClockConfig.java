package com.fivexbread.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 시각을 옮겨야 하는 테스트가 @Import 한다. 같은 설정을 쓰는 테스트끼리 스프링 컨텍스트를 함께 쓴다 */
@TestConfiguration
public class MutableClockConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
