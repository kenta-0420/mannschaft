package com.mannschaft.app.actionmemo;

import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * F02.5 行動メモ機能のメトリクス一元管理。
 *
 * <p>Micrometer の Counter / Gauge を保持し、Service からカウントアップされる。
 * Prometheus / Actuator 経由で取得される想定。</p>
 *
 * <ul>
 *   <li>{@code action_memo_created_total} — メモ作成数のカウンター</li>
 *   <li>{@code action_memo_publish_daily_total} — 終業投稿の成否（Phase 2）</li>
 *   <li>{@code action_memo_weekly_summary_generated_total} — 週次まとめバッチ成功数（Phase 3）</li>
 *   <li>{@code action_memo_weekly_summary_skipped_total} — 週次まとめバッチスキップ数（Phase 3）</li>
 *   <li>{@code action_memo_weekly_summary_failed_total} — 週次まとめバッチ失敗数（Phase 3）</li>
 *   <li>{@code action_memo_mood_enabled_users} — mood_enabled = true のユーザー数（gauge）</li>
 * </ul>
 */
@Component
public class ActionMemoMetrics {

    private final MeterRegistry meterRegistry;
    private final UserActionMemoSettingsRepository settingsRepository;

    /**
     * 明示コンストラクタで {@code settingsRepository} を {@code @Lazy} 注入し、早期初期化の連鎖を断つ。
     *
     * <p>認可基盤 Phase 2 で {@code @EnableMethodSecurity} を点火すると、AOP/BeanPostProcessor の登録に伴い
     * 本 Bean が Spring Data JPA リポジトリ登録より前に生成されようとして
     * {@code No qualifying bean of type 'UserActionMemoSettingsRepository'} で ApplicationContext 全体の
     * 起動に失敗していた（CI で多数の {@code @SpringBootTest} が Failed to load ApplicationContext）。</p>
     *
     * <p><b>注意</b>: 本プロジェクトには {@code lombok.config} が無く {@code lombok.copyableAnnotations} が
     * 未設定のため、フィールドに {@code @Lazy} を付けても {@code @RequiredArgsConstructor} 生成コンストラクタの
     * 引数へは伝播しない（実測で確認）。そのため Lombok ではなく明示コンストラクタを用い、引数に直接
     * {@code @Lazy} を付与する（{@code ShiftBudgetFailedEventService} と同じ流儀）。</p>
     */
    public ActionMemoMetrics(MeterRegistry meterRegistry,
                             @Lazy UserActionMemoSettingsRepository settingsRepository) {
        this.meterRegistry = meterRegistry;
        this.settingsRepository = settingsRepository;
    }

    private Counter createdCounter;
    private Counter publishDailySuccessCounter;
    private Counter publishDailyErrorCounter;
    private Counter dailyLimitExceededCounter;
    private Counter weeklySummaryGeneratedCounter;
    private Counter weeklySummarySkippedCounter;
    private Counter weeklySummaryFailedCounter;

    /** mood_enabled ユーザー数を保持する AtomicLong（Gauge が参照） */
    private final AtomicLong moodEnabledUserCount = new AtomicLong(0L);

    @PostConstruct
    void init() {
        this.createdCounter = Counter.builder("action_memo_created_total")
                .description("行動メモ作成数")
                .register(meterRegistry);
        this.publishDailySuccessCounter = Counter.builder("action_memo_publish_daily_total")
                .tag("result", "success")
                .description("終業投稿の成功数")
                .register(meterRegistry);
        this.publishDailyErrorCounter = Counter.builder("action_memo_publish_daily_total")
                .tag("result", "error")
                .description("終業投稿の失敗数")
                .register(meterRegistry);
        this.dailyLimitExceededCounter = Counter.builder("action_memo_daily_limit_exceeded_total")
                .description("1日200件上限到達数")
                .register(meterRegistry);
        this.weeklySummaryGeneratedCounter = Counter.builder("action_memo_weekly_summary_generated_total")
                .description("週次まとめバッチの成功数（Phase 3）")
                .register(meterRegistry);
        this.weeklySummarySkippedCounter = Counter.builder("action_memo_weekly_summary_skipped_total")
                .description("週次まとめバッチでメモ0件等によりスキップした数（Phase 3）")
                .register(meterRegistry);
        this.weeklySummaryFailedCounter = Counter.builder("action_memo_weekly_summary_failed_total")
                .description("週次まとめバッチで個別ユーザーの生成失敗数（Phase 3）")
                .register(meterRegistry);

        Gauge.builder("action_memo_mood_enabled_users", moodEnabledUserCount, AtomicLong::get)
                .description("mood_enabled = true のユーザー数")
                .register(meterRegistry);
    }

    /**
     * アプリ起動完了後に mood_enabled ユーザー数 gauge の初期値を計算する。
     *
     * <p>以前は {@code @PostConstruct init()} 内で {@link #refreshMoodEnabledUserCount()} を直接呼んで
     * いたが、それだと Bean 初期化のタイミングでリポジトリ（DB）へアクセスしてしまう。
     * Phase 2 点火による早期初期化との競合を確実に避けるため、全 Bean の配線が完了する
     * {@link ApplicationReadyEvent} まで初期計算を遅延させる。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    void initMoodEnabledUserCountOnReady() {
        refreshMoodEnabledUserCount();
    }

    /**
     * メモ作成カウンターをインクリメントする。
     */
    public void incrementCreated() {
        createdCounter.increment();
    }

    /**
     * publish-daily 成功カウンターをインクリメントする（Phase 2）。
     */
    public void incrementPublishDailySuccess() {
        publishDailySuccessCounter.increment();
    }

    /**
     * publish-daily 失敗カウンターをインクリメントする（Phase 2）。
     */
    public void incrementPublishDailyError() {
        publishDailyErrorCounter.increment();
    }

    /**
     * 1日上限到達カウンターをインクリメントする。
     */
    public void incrementDailyLimitExceeded() {
        dailyLimitExceededCounter.increment();
    }

    /**
     * 週次まとめバッチの成功を記録する（Phase 3）。
     */
    public void recordWeeklySummaryGenerated() {
        weeklySummaryGeneratedCounter.increment();
    }

    /**
     * 週次まとめバッチのスキップを記録する（Phase 3）。
     * 主にメモ0件ユーザーに対して呼び出される。
     */
    public void recordWeeklySummarySkipped() {
        weeklySummarySkippedCounter.increment();
    }

    /**
     * 週次まとめバッチの失敗を記録する（Phase 3）。
     * 個別ユーザー単位の例外で呼び出される（バッチ全体の失敗ではない）。
     */
    public void recordWeeklySummaryFailed() {
        weeklySummaryFailedCounter.increment();
    }

    /**
     * mood_enabled ユーザー数 gauge を再計算する。
     * 設定変更時 / 定期バッチから呼び出されることを想定。
     */
    public void refreshMoodEnabledUserCount() {
        try {
            moodEnabledUserCount.set(settingsRepository.countByMoodEnabledTrue());
        } catch (Exception e) {
            // 起動直後に DB がまだ準備できていない場合があるので黙殺
            moodEnabledUserCount.set(0L);
        }
    }
}
