package com.fivexbread;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FivexbreadApplication {

    public static void main(String[] args) {
        SpringApplication.run(FivexbreadApplication.class, args);
    }
}
