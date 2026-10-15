package com.fivexbread;

import com.fivexbread.support.TestcontainersConfig;
import org.junit.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
public class FivexbreadApplicationTests {

    @Test
    public void contextLoads() {
        // 앱이 뜨고 Flyway V1·V2가 실제 MySQL에 적용되면 통과
    }
}