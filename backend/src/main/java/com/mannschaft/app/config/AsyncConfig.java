package com.mannschaft.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 非同期処理設定。
 *
 * <p>イベント駆動（ApplicationEvent）用とバッチジョブ用の2つのスレッドプールを定義する。
 * Virtual Threads は application.yml の {@code spring.threads.virtual.enabled=true} で有効化済み。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * イベント処理用スレッドプール。
     * ApplicationEvent の非同期リスナーで使用する。
     */
    @Bean("event-pool")
    public Executor eventPoolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("event-");
        executor.initialize();
        return executor;
    }

    /**
     * バッチジョブ用スレッドプール。
     * 定期実行タスクや重い処理に使用する。
     */
    @Bean("job-pool")
    public Executor jobPoolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("job-");
        executor.initialize();
        return executor;
    }
}
