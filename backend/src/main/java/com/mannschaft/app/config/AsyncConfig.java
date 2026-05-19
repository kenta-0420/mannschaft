package com.mannschaft.app.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 非同期処理設定。
 *
 * <p>イベント駆動（ApplicationEvent）用とバッチジョブ用の2つのスレッドプールを定義する。
 * Virtual Threads は application.yml の {@code spring.threads.virtual.enabled=true} で有効化済み。</p>
 *
 * <p>F10.5/F10.6 Phase 10-β 後続: 両プールに {@link MdcTaskDecorator} を適用し、
 * 呼び出し側スレッドの MDC（requestId / traceId 等）を非同期スレッドに伝播する。
 * これにより {@code @Async} 化された
 * {@link com.mannschaft.app.errorreport.service.ErrorReportService#recordBackendException}
 * 等が requestId を引き続き拾えるようになる。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * イベント処理用スレッドプール。
     * ApplicationEvent の非同期リスナーで使用する。
     */
    @Primary
    @Bean("event-pool")
    public Executor eventPoolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("event-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 退会フロー専用スレッドプール（Phase W-A 前提インフラ）。
     *
     * <p>退会即時匿名化（{@code UserAnonymizedEvent}）配下の 9 ドメインリスナーを
     * {@code event-pool} から物理分離するための専用プール。退会バースト時に
     * Webhook 配信・通知配信などの他機能を圧迫することを防ぐ。</p>
     *
     * <p>設計根拠: {@code docs/architecture/withdrawal_flow_immediate_anonymization_fix.md}
     * §13.10（マスター御裁可 2026-05-18: A + B 両方採用）。
     * {@code @Async("withdrawal-pool")} を退会経路リスナーに指定して切替える運用とする。</p>
     *
     * <p>サイジング: corePoolSize=2 / maxPoolSize=10 / queueCapacity=100 で
     * 想定退会同時実行 100 件をキャパに収める。1 退会あたり 9 ドメイン × 数百 ms
     * を見込み、ピーク 10 並列で吸収する。</p>
     */
    @Bean("withdrawal-pool")
    public Executor withdrawalPoolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("withdrawal-");
        executor.setTaskDecorator(new MdcTaskDecorator());
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
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 呼び出し元スレッドの MDC コンテキストを非同期スレッドに引き継ぐ {@link TaskDecorator}。
     *
     * <p>{@code @Async} メソッド内で {@code MDC.get("requestId")} 等を読みたいケース
     * （{@link com.mannschaft.app.errorreport.service.ErrorReportService#recordBackendException}）
     * で必要になる。タスク実行終了時には実行スレッド側の MDC をクリアし、
     * プール内スレッドが再利用された際に古いコンテキストを誤って引き継がないようにする。</p>
     */
    static class MdcTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> snapshot = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (snapshot != null) {
                        MDC.setContextMap(snapshot);
                    } else {
                        MDC.clear();
                    }
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        }
    }
}
