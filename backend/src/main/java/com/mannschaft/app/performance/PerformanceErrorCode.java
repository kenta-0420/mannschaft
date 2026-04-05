package com.mannschaft.app.performance;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F07.2 パフォーマンス管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum PerformanceErrorCode implements ErrorCode {

    /** 指標が見つからない */
    METRIC_NOT_FOUND("PERF_001", "指標が見つかりません", Severity.WARN),

    /** 記録が見つからない */
    RECORD_NOT_FOUND("PERF_002", "記録が見つかりません", Severity.WARN),

    /** 指標上限超過 */
    METRIC_LIMIT_EXCEEDED("PERF_003", "有効な指標は30件までです", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("PERF_004", "この操作に必要な権限がありません", Severity.WARN),

    /** ユーザーがチームに所属していない */
    USER_NOT_IN_TEAM("PERF_005", "ユーザーがチームに所属していません", Severity.WARN),

    /** 自己記録不可の指標 */
    SELF_RECORD_NOT_ALLOWED("PERF_006", "この指標はメンバーによる自己記録が許可されていません", Severity.WARN),

    /** 一括バリデーションエラー */
    BULK_VALIDATION_FAILED("PERF_007", "一括入力のバリデーションに失敗しました", Severity.WARN),

    /** INTEGER型に小数値 */
    INTEGER_VALUE_REQUIRED("PERF_008", "INTEGER型の指標に小数値は入力できません", Severity.WARN),

    /** 値範囲外 */
    VALUE_OUT_OF_RANGE("PERF_009", "値が許容範囲外です", Severity.WARN),

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("PERF_010", "指定されたスポーツカテゴリのテンプレートが見つかりません", Severity.WARN),

    /** スケジュールが見つからない */
    SCHEDULE_NOT_FOUND("PERF_011", "スケジュールが見つかりません", Severity.WARN),

    /** 指標が無効化されている */
    METRIC_INACTIVE("PERF_012", "この指標は無効化されています", Severity.WARN),

    /** 活動記録が見つからない */
    ACTIVITY_NOT_FOUND("PERF_013", "活動記録が見つかりません", Severity.WARN),

    /** チームが見つからない */
    TEAM_NOT_FOUND("PERF_014", "チームが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
