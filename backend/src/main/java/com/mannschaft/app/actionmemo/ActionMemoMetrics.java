package com.mannschaft.app.actionmemo;

import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
 *   <li>{@code action_memo_mood_enabled_users} — mood_enabled = true のユーザー数（gauge）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ActionMemoMetrics {

    private final MeterRegistry meterRegistry;
    private final UserActionMemoSettingsRepository settingsRepository;

    private Counter createdCounter;
    private Counter publishDailySuccessCounter;
    private Counter publishDailyErrorCounter;
    private Counter dailyLimitExceededCounter;

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

        Gauge.builder("action_memo_mood_enabled_users", moodEnabledUserCount, AtomicLong::get)
                .description("mood_enabled = true のユーザー数")
                .register(meterRegistry);

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
