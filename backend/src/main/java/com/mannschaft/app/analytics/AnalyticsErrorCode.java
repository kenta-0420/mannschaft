package com.mannschaft.app.analytics;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 経営分析機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum AnalyticsErrorCode implements ErrorCode {

    /** アラートルールが見つからない */
    ANALYTICS_001("ANALYTICS_001", "指定されたアラートルールが見つかりません", Severity.WARN),

    /** スナップショットが見つからない */
    ANALYTICS_002("ANALYTICS_002", "指定された月のスナップショットが見つかりません", Severity.WARN),

    /** バックフィル実行中 */
    ANALYTICS_003("ANALYTICS_003", "バックフィルが既に実行中です", Severity.WARN),

    /** 日付範囲超過 */
    ANALYTICS_004("ANALYTICS_004", "指定された日付範囲が上限を超えています", Severity.WARN),

    /** 日付範囲不正 */
    ANALYTICS_005("ANALYTICS_005", "開始日は終了日より前に指定してください", Severity.WARN),

    /** 予測データ不足 */
    ANALYTICS_006("ANALYTICS_006", "予測に必要なデータが不足しています", Severity.INFO),

    /** エクスポート期間超過 */
    ANALYTICS_007("ANALYTICS_007", "エクスポートの最大期間は1年です", Severity.WARN),

    /** 不正な予測月数 */
    ANALYTICS_008("ANALYTICS_008", "予測月数は 3, 6, 12 のいずれかを指定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
