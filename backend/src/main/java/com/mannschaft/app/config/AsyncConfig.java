package com.mannschaft.app.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

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
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 既定 AbortPolicy（呼び出し元へ {@code RejectedExecutionException} を投げ返す）。
     * 状態を持たないハンドラなので拒否のたびに new せず定数として使い回す。
     */
    private static final RejectedExecutionHandler ABORT_POLICY = new ThreadPoolExecutor.AbortPolicy();

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
        // Issue #2953: 投入拒否を silent drop にしない。
        // 拒否方針そのもの（AbortPolicy = 例外送出）は 168 箇所の @Async("event-pool") 全体に効くため変更せず、
        // 「拒否が起きた事実」を構造化 ERROR ログで観測できるようにするだけに留める。
        executor.setRejectedExecutionHandler((runnable, poolExecutor) -> {
            log.error("event-pool 投入拒否: pool_saturated event=async_task_rejected pool=event-pool "
                            + "activeCount={} poolSize={} queueSize={} completedTaskCount={} task={}",
                    poolExecutor.getActiveCount(), poolExecutor.getPoolSize(),
                    poolExecutor.getQueue().size(), poolExecutor.getCompletedTaskCount(),
                    runnable.getClass().getName());
            // 既定 AbortPolicy と同じ意味論（呼び出し元へ例外を返す）を維持する。
            ABORT_POLICY.rejectedExecution(runnable, poolExecutor);
        });
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
     * <p><b>MeterRegistry は {@link ObjectProvider} で optional 解決する</b>:
     * {@code @SpringBootTest(classes=...)} の narrowed context には Micrometer の
     * {@code MeterRegistry} Bean が無いことがある。直接注入すると pool 生成が
     * {@code UnsatisfiedDependencyException} で失敗し、無関係なテスト（narrowed context 全般）を
     * 巻き添えにする。そのため {@code getIfAvailable()} で null 許容とし、レジストリが
     * 無い場合は可視化カウンタの登録だけをスキップする（pool 生成は常に成功する）。
     * 作法は {@code common.ratelimit.ValkeyRateLimiter} の
     * {@code ObjectProvider<MeterRegistry>} に倣う。</p>
     *
     * @param meterRegistryProvider Discard 件数カウンタ登録用 Micrometer レジストリの optional プロバイダ
     * @return page-view-pool エグゼキュータ
     */
    @Bean("page-view-pool")
    public Executor pageViewPool(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        // レジストリが利用可能なときだけ可視化カウンタを用意する（narrowed context では null 許容）。
        Counter discardedCounter = meterRegistry == null ? null
                : Counter.builder("mannschaft.pageview.discarded")
                        .description("page-view-pool 飽和時に破棄されたページビュー計測タスク数（欠損許容・可視化目的）")
                        .register(meterRegistry);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500); // SPA ユーザー 500 人同時閲覧分のバースト吸収
        executor.setThreadNamePrefix("page-view-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        // DiscardPolicy 相当（飽和時は捨てる）＋捨てた数を可視化。既存プールは AbortPolicy 既定なので明示必須。
        // カウンタが無い（レジストリ欠落）環境では捨てるだけで可視化はスキップする。
        executor.setRejectedExecutionHandler((runnable, poolExecutor) -> {
            if (discardedCounter != null) {
                discardedCounter.increment();
            }
        });
        executor.initialize();
        return executor;
    }

    /**
     * 通知 fan-out 配信専用スレッドプール（fan-out 抜本改修 P1）。
     *
     * <p>村行事・アンケート・予定リマインド等の一斉配信（{@code notifyAllPreAuthorized}）は、
     * 受信者チャンク単位の配信タスクを本プールへ投入する。監査ログ（{@code AuditLogEventListener}）や
     * 退会処理と共用する {@code event-pool}（AbortPolicy 既定）から<b>物理分離</b>し、
     * 50 万人規模のバースト配信が他機能を巻き添えにするのを防ぐ（設計: 台帳 2026-07-29-fanout-redesign-500k）。</p>
     *
     * <p><b>棄却は「静かに捨てない」</b>: 通知は欠損許容ではない（page-view のような計測ビーコンと異なる）ため、
     * 飽和時は既定 AbortPolicy（例外を握り潰す silent drop）を採らず、<b>CallerRuns 相当で取りこぼさず</b>
     * 実行しつつ、飽和回数を Micrometer カウンタ {@code mannschaft.notification.fanout.pool.saturated} で
     * <b>可視化</b>する（{@code page-view-pool} の可視化パターンを踏襲。ただし Discard ではなく CallerRuns）。
     * 呼び出しスレッドで実行することで自然な背圧がかかり、キューが空くまで投入側がペーシングされる。</p>
     *
     * <p>サイジング: corePoolSize=4 / maxPoolSize=8 / queueCapacity=500
     * （{@code purge-pool} / {@code page-view-pool} 前例の queue500 に揃える）。1 タスク=1 チャンク配信
     * （数百件の WebSocket/Push）。キュー溢れは CallerRuns で吸収し欠損させない。</p>
     *
     * <p><b>MeterRegistry は {@link ObjectProvider} で optional 解決する</b>: narrowed な
     * {@code @SpringBootTest} context には {@code MeterRegistry} が無いことがあり、直接注入すると
     * pool 生成が {@code UnsatisfiedDependencyException} で失敗して無関係なテストを巻き添えにするため
     * （{@code page-view-pool} と同じ作法）。</p>
     *
     * @param meterRegistryProvider 飽和回数カウンタ登録用 Micrometer レジストリの optional プロバイダ
     * @return notification-fanout-pool エグゼキュータ
     */
    @Bean("notification-fanout-pool")
    public Executor notificationFanoutPool(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        Counter saturatedCounter = meterRegistry == null ? null
                : Counter.builder("mannschaft.notification.fanout.pool.saturated")
                        .description("notification-fanout-pool 飽和時に CallerRuns で実行されたチャンク配信タスク数（欠損させず可視化）")
                        .register(meterRegistry);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500); // purge-pool / page-view-pool 前例に揃える
        executor.setThreadNamePrefix("notification-fanout-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        // 通知は欠損許容でない。飽和時は AbortPolicy（silent drop）ではなく CallerRuns で取りこぼさず実行し、
        // 飽和回数だけをカウンタで可視化する（レジストリ欠落環境では実行のみ・可視化はスキップ）。
        executor.setRejectedExecutionHandler((runnable, poolExecutor) -> {
            if (saturatedCounter != null) {
                saturatedCounter.increment();
            }
            if (!poolExecutor.isShutdown()) {
                runnable.run();
            }
        });
        executor.initialize();
        return executor;
    }

    /**
     * 通知<b>単発</b>配信（{@link com.mannschaft.app.notification.service.NotificationDispatchService#dispatch}）
     * 専用スレッドプール（Issue #2953）。
     *
     * <h2>なぜ分けるのか — event-pool の自己飽和</h2>
     * <p>CMP-056 で確立した通知配送の型は
     * 「{@code AFTER_COMMIT} + {@code @Async("event-pool")} の配送リスナー」→
     * 「{@link com.mannschaft.app.notification.service.NotificationDeliveryRunner#sendOne}
     * （{@code REQUIRES_NEW}）」→「{@code dispatch}（{@code @Async}）」という形をとる。
     * {@code dispatch} が executor 無指定だと {@code @Primary} により <b>呼び出し元と同じ event-pool</b>
     * へ再投入される（自己投入）。配送リスナーが event-pool のワーカーを占有したまま同じプールへ積むため、
     * 受信者が多い経路では容易に自己飽和する。飽和すると既定 AbortPolicy が
     * {@code RejectedExecutionException} を同期で投げ返し、それが {@code sendOne} の
     * {@code REQUIRES_NEW} トランザクションを巻き戻して<b>作成済みの通知行そのものが消える</b>。</p>
     *
     * <h2>採った解</h2>
     * <ol>
     *   <li><b>物理分離</b>: {@code dispatch} を本プールへ移し、配送リスナー（event-pool）と
     *       スレッドを奪い合わせない。自己投入が構造的に成立しなくなる。</li>
     *   <li><b>CallerRuns</b>: それでも本プールが飽和した場合は、通知は欠損許容でないため捨てず、
     *       呼び出し元スレッド（= event-pool ワーカー）で同期実行する。例外が発生しないため
     *       {@code sendOne} の {@code REQUIRES_NEW} は決してロールバックせず、<b>通知行は残る</b>。
     *       同時に投入側へ自然な背圧がかかる。</li>
     *   <li><b>ERROR ログでの可視化</b>: 飽和は異常事態なので構造化 ERROR ログを残す
     *       （本戦役では Micrometer カウンタを増やさない方針のため、可視化はログで行う）。</li>
     * </ol>
     *
     * <p>一括配信（{@code dispatchBatch}）は従来どおり {@code notification-fanout-pool} を使う。
     * 本プールは<b>単発配信専用</b>であり、バルク経路の設計には手を触れていない。</p>
     *
     * <h2>サイジング根拠</h2>
     * <p>corePoolSize=4 / maxPoolSize=8 / queueCapacity=500。
     * 1 タスク = 受信者 1 名への WebSocket/Push 送信で、DB アクセスは設定/種別/購読の読み取りのみ。
     * 最大 8 並列は CI の Hikari プール 5 本（{@code application-ci.yml:25}）に対して過剰に見えるが、
     * CallerRuns によりこれ以上の同時実行は投入側の背圧で自動的に抑えられる。無制限キューは
     * OOM の入口になるため採らず、既存プール（{@code purge-pool} / {@code page-view-pool} /
     * {@code notification-fanout-pool}）の前例に揃えて 500 で上限を切る。</p>
     *
     * <h2>CallerRuns の代償（Issue #2953 検分指摘1）</h2>
     * <p>本プールのタスクは<b>短命な読み取りではない</b>。{@code dispatch} は最後に
     * {@link com.mannschaft.app.notification.service.WebPushService#sendPushNotification}
     * を呼び、これは<b>同期 HTTP + 429/5xx 時のリトライ + バックオフ sleep</b> を伴う。
     * したがって CallerRuns が発火すると、外向き HTTP が
     * {@link com.mannschaft.app.notification.service.NotificationDeliveryRunner#sendOne} の
     * {@code REQUIRES_NEW} トランザクションの<b>内側</b>で、呼び出し元（= {@code event-pool}
     * ワーカー）スレッドにより同期実行される。帰結として</p>
     * <ul>
     *   <li>Hikari コネクションを push の HTTP 往復とバックオフのあいだ保持し続ける</li>
     *   <li>{@code event-pool}（maxPoolSize=5・AbortPolicy）のワーカーが塞がれ、
     *       飽和の圧力が本プールから {@code event-pool} 側へ移りうる</li>
     *   <li>410/404 時の {@code pushSubscriptionRepository.deleteByEndpoint} が、
     *       インライン実行時は通知トランザクションに参加する（非同期実行時と境界が変わる）</li>
     * </ul>
     * <p>危険なのは接続の<b>本数</b>ではなく<b>保持時間</b>である。そのため
     * {@code WebPushService} 側に 1 リクエスト 10 秒・1 通知あたり総予算 30 秒の上限を課し、
     * 保持時間を上に有界にしてある（予算超過時は例外を投げず諦める。例外を投げると
     * {@code REQUIRES_NEW} ごと巻き戻り通知行が消えるため）。
     * push の HTTP をトランザクション境界の外へ出す本筋の是正は別 issue（#2998）とする。</p>
     *
     * @return notification-dispatch-pool エグゼキュータ
     */
    @Bean("notification-dispatch-pool")
    public Executor notificationDispatchPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500); // notification-fanout-pool / purge-pool の前例に揃える
        executor.setThreadNamePrefix("notification-dispatch-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        // 通知は欠損許容ではない。飽和時も捨てず・例外も投げず、呼び出し元スレッドで実行する（CallerRuns）。
        // 例外を投げないことが本質: 投げると sendOne の REQUIRES_NEW ごとロールバックし通知行が消える。
        executor.setRejectedExecutionHandler((runnable, poolExecutor) -> {
            log.error("notification-dispatch-pool 投入拒否: pool_saturated event=async_task_rejected "
                            + "pool=notification-dispatch-pool policy=caller_runs "
                            + "activeCount={} poolSize={} queueSize={} completedTaskCount={}",
                    poolExecutor.getActiveCount(), poolExecutor.getPoolSize(),
                    poolExecutor.getQueue().size(), poolExecutor.getCompletedTaskCount());
            if (!poolExecutor.isShutdown()) {
                runnable.run();
            }
        });
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
