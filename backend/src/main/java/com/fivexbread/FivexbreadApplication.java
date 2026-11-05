package com.fivexbread;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// 로그인은 JWT 만 쓴다. 기본 사용자(user)를 만들면 시작 로그에 생성된 비밀번호가 원문으로 찍혀서 끈다
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class FivexbreadApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));   // DB·Clock 과 같은 KST 로 고정 (decisions.md 3장)
        SpringApplication.run(FivexbreadApplication.class, args);
    }
}
