package com.mannschaft.app.errorreport;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * F12.5 Phase 2 — エラーレポート機能の設定プロパティ。
 *
 * <p>{@code mannschaft.error-report.*} 配下を {@link Ai} / {@link GitHub} に
 * バインドする。</p>
 */
@ConfigurationProperties(prefix = "mannschaft.error-report")
@Component
@Getter
@Setter
public class ErrorReportProperties {

    /** AI 分析関連の設定。 */
    private final Ai ai = new Ai();

    /** GitHub 連携関連の設定。 */
    private final GitHub github = new GitHub();

    /**
     * AI 分析関連の設定。
     */
    @Getter
    @Setter
    public static class Ai {
        /** AI 分析機能の有効化フラグ。 */
        private boolean enabled = true;

        /** Claude モデル名。 */
        private String model = "claude-haiku-4-5";

        /** 1 リクエストあたりの最大トークン数。 */
        private int maxTokens = 1500;

        /** 推論温度（0.0〜1.0）。 */
        private double temperature = 0.2;

        /** 月次予算（円）。 */
        private int monthlyBudgetJpy = 5000;

        /** 自動分析バッチが拾うまでの遅延時間（分）。 */
        private int autoBatchDelayMinutes = 30;

        /** Claude API 呼び出しのタイムアウト（ミリ秒、既定 5 秒）。 */
        private int timeoutMs = 5000;

        /** Claude API 呼び出しのリトライ最大試行回数（既定 3）。 */
        private int retryMaxAttempts = 3;

        /** リトライ初回バックオフ遅延（ミリ秒、指数バックオフの基点。既定 200ms）。 */
        private int retryBackoffDelayMs = 200;
    }

    /**
     * GitHub Issue 連携関連の設定。
     */
    @Getter
    @Setter
    public static class GitHub {
        /** GitHub 連携機能の有効化フラグ。 */
        private boolean enabled = false;
    }
}
