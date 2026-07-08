package com.proactiveguardian;

import com.proactiveguardian.config.GuardianProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
@EnableConfigurationProperties(GuardianProperties.class)
public class GuardianApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuardianApplication.class, args);
    }
}

