package com.proactiveguardian.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Provides the {@code guardianWorker} executor used by {@code @Async} PR-processing
 * calls (replacement for FastAPI's {@code BackgroundTasks}).
 */
@Configuration
public class AsyncConfig {

    @Bean("guardianWorker")
    public Executor guardianWorker() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("guardian-");
        exec.initialize();
        return exec;
    }
}

