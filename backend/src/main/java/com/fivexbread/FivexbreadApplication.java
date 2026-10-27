package com.fivexbread;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FivexbreadApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));   // DB·Clock 과 같은 KST 로 고정 (decisions.md 3장)
        SpringApplication.run(FivexbreadApplication.class, args);
    }
}
