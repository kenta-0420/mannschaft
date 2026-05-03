package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.7 シフト-予算-TODO 連携機能のエラーコード定義。
 *
 * <p>Phase 9-α の逆算 API ({@code POST /api/v1/shift-budget/calc/required-slots}) で利用する
 * バリデーション・フィーチャーフラグ系のエラーコードのみを定義する。
 * 後続の Phase 9-β/γ/δ で消化記録・紐付・警告系のコードを追加予定。</p>
 *
 * <p>HTTP ステータスマッピングは
 * {@link com.mannschaft.app.common.GlobalExceptionHandler#ERROR_CODE_STATUS_MAP} に登録する。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ShiftBudgetErrorCode implements ErrorCode {

    /** フィーチャーフラグ OFF 時の API 呼出 (HTTP 503) */
    FEATURE_DISABLED("SHIFT_BUDGET_001",
            "シフト予算機能は無効化されています", Severity.WARN),

    /** position_required_counts が空配列 (HTTP 400) */
    EMPTY_POSITION_LIST("SHIFT_BUDGET_002",
            "ポジション別必要人数を1件以上指定してください", Severity.WARN),

    /** position_required_counts に重複する position_id がある (HTTP 400) */
    DUPLICATE_POSITION_ID("SHIFT_BUDGET_003",
            "重複するポジションIDが指定されています", Severity.WARN),

    /** required_count が 0 以下 (HTTP 400) */
    INVALID_REQUIRED_COUNT("SHIFT_BUDGET_004",
            "必要人数は1以上で指定してください", Severity.WARN),

    /** スロット時間が範囲外 (HTTP 400) */
    INVALID_SLOT_HOURS("SHIFT_BUDGET_005",
            "スロット時間は0.25時間以上24時間以下で指定してください", Severity.WARN),

    /** EXPLICIT モードで avg_hourly_rate が未指定 (HTTP 400) */
    MISSING_EXPLICIT_RATE("SHIFT_BUDGET_006",
            "EXPLICITモードでは avg_hourly_rate の指定が必須です", Severity.WARN),

    /** POSITION_AVG モードで position_required_counts が未指定 (HTTP 400) */
    MISSING_POSITION_COUNTS("SHIFT_BUDGET_007",
            "POSITION_AVGモードでは position_required_counts の指定が必須です", Severity.WARN),

    /** チームが存在しない / 組織スコープ不一致 (HTTP 404 IDOR 対策) */
    TEAM_NOT_FOUND("SHIFT_BUDGET_008",
            "対象のチームが見つかりません", Severity.WARN),

    /** 予算金額が負数 (HTTP 400) */
    INVALID_BUDGET_AMOUNT("SHIFT_BUDGET_009",
            "予算額は0以上で指定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
