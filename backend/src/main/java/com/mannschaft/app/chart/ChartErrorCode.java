package com.mannschaft.app.chart;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F07.4 カルテのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ChartErrorCode implements ErrorCode {

    /** カルテが見つからない */
    CHART_NOT_FOUND("CHART_001", "カルテが見つかりません", Severity.WARN),

    /** 写真が見つからない */
    PHOTO_NOT_FOUND("CHART_002", "写真が見つかりません", Severity.WARN),

    /** 薬剤レシピが見つからない */
    FORMULA_NOT_FOUND("CHART_003", "薬剤レシピが見つかりません", Severity.WARN),

    /** カスタムフィールドが見つからない */
    CUSTOM_FIELD_NOT_FOUND("CHART_004", "カスタムフィールドが見つかりません", Severity.WARN),

    /** 問診票テンプレートが見つからない */
    INTAKE_FORM_TEMPLATE_NOT_FOUND("CHART_005", "問診票テンプレートが見つかりません", Severity.WARN),

    /** カルテテンプレートが見つからない */
    RECORD_TEMPLATE_NOT_FOUND("CHART_006", "カルテテンプレートが見つかりません", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("CHART_007", "この操作に必要な権限がありません", Severity.WARN),

    /** セクションが無効 */
    SECTION_DISABLED("CHART_008", "このセクションは無効です", Severity.WARN),

    /** 写真枚数上限超過 */
    PHOTO_LIMIT_EXCEEDED("CHART_009", "写真枚数の上限（20枚）を超えています", Severity.WARN),

    /** 身体マーク上限超過 */
    BODY_MARK_LIMIT_EXCEEDED("CHART_010", "身体マークの上限（50件）を超えています", Severity.WARN),

    /** 薬剤レシピ上限超過 */
    FORMULA_LIMIT_EXCEEDED("CHART_011", "薬剤レシピの上限（20件）を超えています", Severity.WARN),

    /** カスタムフィールド上限超過 */
    CUSTOM_FIELD_LIMIT_EXCEEDED("CHART_012", "カスタムフィールドの上限（5件）を超えています", Severity.WARN),

    /** 問診票テンプレート上限超過 */
    INTAKE_TEMPLATE_LIMIT_EXCEEDED("CHART_013", "問診票テンプレートの上限（10件）を超えています", Severity.WARN),

    /** カルテテンプレート上限超過 */
    RECORD_TEMPLATE_LIMIT_EXCEEDED("CHART_014", "カルテテンプレートの上限（20件）を超えています", Severity.WARN),

    /** 顧客がチームに所属していない */
    CUSTOMER_NOT_IN_TEAM("CHART_015", "顧客がこのチームに所属していません", Severity.WARN),

    /** ピン留め上限超過 */
    PIN_LIMIT_EXCEEDED("CHART_016", "ピン留めの上限（5件）を超えています", Severity.WARN),

    /** ファイル形式不正 */
    INVALID_FILE_TYPE("CHART_017", "対応していないファイル形式です", Severity.WARN),

    /** ファイルサイズ超過 */
    FILE_SIZE_EXCEEDED("CHART_018", "ファイルサイズの上限（10MB）を超えています", Severity.WARN),

    /** 問診票が見つからない */
    INTAKE_FORM_NOT_FOUND("CHART_019", "問診票が見つかりません", Severity.WARN),

    /** 楽観的ロック競合 */
    OPTIMISTIC_LOCK_CONFLICT("CHART_020", "他のユーザーによって更新されています。再読み込みしてください", Severity.WARN),

    /** デフォルトテンプレート重複 */
    DUPLICATE_DEFAULT_TEMPLATE("CHART_021", "同じ種別のデフォルトテンプレートが既に存在します", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
