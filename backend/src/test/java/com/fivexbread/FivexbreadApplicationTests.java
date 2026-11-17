package com.fivexbread;

import com.fivexbread.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

/**
 * 앱 전체 컨텍스트가 test 프로필로 뜨는지 본다. JUnit 4(org.junit.Test)였을 때는 JUnit Platform 이 찾지 못해 실행되지 않았다.
 * IntegrationTestSupport 를 상속해 test 프로필(비밀 키)·컨테이너를 쓴다. 기본 프로필이 없으므로 프로필을 직접 켜야 뜬다.
 */
class FivexbreadApplicationTests extends IntegrationTestSupport {

    @Test
    void contextLoads() {
        // 앱이 뜨고 Flyway 마이그레이션 전체가 실제 MySQL 에 적용되면 통과
    }
}
