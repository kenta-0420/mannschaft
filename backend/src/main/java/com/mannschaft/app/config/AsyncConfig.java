package com.mannschaft.app.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
     * 退会物理削除（{@code AccountPurgedEvent}）専用スレッドプール（Phase D-1）。
     *
     * <p>退会バッチ（{@code AccountPurgeService}、04:00 JST）が 100 件 × 6 ドメイン
     * = 最大 600 タスクを瞬時 enqueue する。{@code event-pool}（queueCapacity=100）では
     * 溢れるため、退会物理削除専用のプールに物理分離する。</p>
     *
     * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md}
     * §9.8 案 A 採用（マスター御裁可 Phase D-1）。
     * {@code @Async("purge-pool")} を 6 本の {@code *PurgeEventListener} に指定して切替え。</p>
     *
     * <p>サイジング: corePoolSize=2 / maxPoolSize=10 / queueCapacity=500 で
     * 100 件バッチ × 6 ドメイン = 600 タスクに対応。1 件あたり数十〜数百 ms を見込み、
     * ピーク 10 並列 × queueCapacity=500 でバースト吸収する。</p>
     */
    @Bean("purge-pool")
    public Executor purgePool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500); // 100件バッチ × 6ドメイン = 600タスクに対応
        executor.setThreadNamePrefix("purge-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * F10.8 アクセス解析 計測ビーコン専用スレッドプール。
     *
     * <p>計測ビーコン {@code POST /api/v1/page-views} の生ログ INSERT を担う
     * {@link com.mannschaft.app.analytics.event.PageViewRecordListener} 専用プール。
     * 監査ログ（{@code AuditLogEventListener} AFTER_COMMIT）と共用する {@code event-pool}（AbortPolicy 既定）から
     * <b>物理分離</b>し、PV バースト時に監査ログ記録まで巻き添えで失敗するのを防ぐ（設計書 §5.1）。</p>
     *
     * <p><b>DiscardPolicy を明示採用</b>: PV は欠損許容（アクセスカウンター性質・課金/監査ではない）のため、
     * 飽和時は静かに捨てる。既存プールは AbortPolicy 既定のため、本プールでは明示的に上書きする。
     * ただし「静かな無効化にしない」方針（{@code docs/security/06 §4.3.1} のレートリミット fail-open 可視化に倣う）に沿い、
     * 捨てた件数を Micrometer カウンタ {@code mannschaft.pageview.discarded} で可視化する。</p>
     *
     * <p>サイジング: corePoolSize=2 / maxPoolSize=8 / queueCapacity=500
     * （SPA ユーザー 500 人同時閲覧分のバースト吸収を想定）。</p>
     *
     * <p><b>テスト時の差し替え</b>: {@code DiscardPolicy} + {@code Awaitility} の組み合わせでは
     * 「タスクが捨てられた」ことを {@code Awaitility} が区別できず偽 green になりうるため、
     * リスナーの結合テストでは非プロキシのリスナーを直接同期呼び出しするか、
     * {@code SyncTaskExecutor} に差し替えて決定論化する（設計書 §2.4 / §5.1）。</p>
     *
     * @param meterRegistry Discard 件数カウンタ登録用の Micrometer レジストリ
     * @return page-view-pool エグゼキュータ
     */
    @Bean("page-view-pool")
    public Executor pageViewPool(MeterRegistry meterRegistry) {
        Counter discardedCounter = Counter.builder("mannschaft.pageview.discarded")
                .description("page-view-pool 飽和時に破棄されたページビュー計測タスク数（欠損許容・可視化目的）")
                .register(meterRegistry);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500); // SPA ユーザー 500 人同時閲覧分のバースト吸収
        executor.setThreadNamePrefix("page-view-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        // DiscardPolicy 相当（飽和時は捨てる）＋捨てた数を可視化。既存プールは AbortPolicy 既定なので明示必須。
        executor.setRejectedExecutionHandler((runnable, poolExecutor) -> discardedCounter.increment());
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
