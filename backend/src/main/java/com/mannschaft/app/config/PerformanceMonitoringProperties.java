package com.mannschaft.app.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * F10.5 Phase 10-β — パフォーマンス監視のアラート閾値設定。
 *
 * <p>設計書 {@code docs/features/F10.5_performance_monitoring.md §5.2.3} の
 * 推奨閾値を {@code application.yml} で外出しし、staging / prod でオーバーライド可能にする。</p>
 */
@ConfigurationProperties(prefix = "mannschaft.performance-monitoring")
@Component
@Getter
@Setter
public class PerformanceMonitoringProperties {

    /** リクエスト経過時間（duration_ms）の閾値。 */
    private RequestThresholds request = new RequestThresholds();

    /** スロークエリ件数（毎分）の閾値。 */
    private SlowQueryThresholds slowQuery = new SlowQueryThresholds();

    /** キャッシュヒット率の閾値。 */
    private CacheHitRateThresholds cacheHitRate = new CacheHitRateThresholds();

    /**
     * リクエスト単発の duration_ms に対する閾値。
     *
     * <p>{@link RequestLoggingFilter} がこれらの閾値で WARN / ERROR ログレベルを切り替え、
     * ERROR を超えた場合に F10.6 ErrorReportNotifier 経由で Slack 通知 + error_reports
     * への記録（severity=HIGH）を行う。</p>
     */
    @Getter
    @Setter
    public static class RequestThresholds {
        /** WARN ログにエスカレートする閾値（ミリ秒）。設計書 §5.2.3 推奨: 2000。 */
        private long warnMs = 2_000L;

        /** ERROR ログ + Slack 通知 + error_reports 記録を発火する閾値（ミリ秒）。設計書 §5.2.3 推奨: 10000。 */
        private long errorMs = 10_000L;
    }

    /**
     * スロークエリ件数（毎分集計）の閾値。
     *
     * <p>Phase 10-β 段階ではこの値を保持するのみで、実際の集計バッチは Phase 10-γ で実装する。
     * 値を保持しておくことで、staging / prod のチューニング時に環境変数で上書き可能にする。</p>
     */
    @Getter
    @Setter
    public static class SlowQueryThresholds {
        /** WARN 通知の毎分件数閾値。設計書 §5.2.3 推奨: 5。 */
        private int warnPerMinute = 5;

        /** ERROR 通知の毎分件数閾値。設計書 §5.2.3 推奨: 30。 */
        private int errorPerMinute = 30;
    }

    /**
     * キャッシュヒット率の閾値。
     *
     * <p>Phase 10-β 段階ではこの値を保持するのみで、実際のヒット率集計バッチは
     * Phase 10-γ で実装する。値の単位は 0.0〜1.0 の比率。</p>
     */
    @Getter
    @Setter
    public static class CacheHitRateThresholds {
        /** WARN 通知のヒット率閾値（これを下回ると WARN）。設計書 §5.2.3 推奨: 0.80。 */
        private double warnThreshold = 0.80;

        /** ERROR 通知のヒット率閾値（これを下回ると ERROR）。設計書 §5.2.3 推奨: 0.50。 */
        private double errorThreshold = 0.50;
    }
}
