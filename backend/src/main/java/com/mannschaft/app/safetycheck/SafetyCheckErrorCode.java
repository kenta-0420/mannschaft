package com.mannschaft.app.safetycheck;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.6 緊急安否確認のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum SafetyCheckErrorCode implements ErrorCode {

    /** 安否確認が見つからない */
    SAFETY_CHECK_NOT_FOUND("SAFETY_001", "安否確認が見つかりません", Severity.WARN),

    /** 安否確認は既にクローズ済み */
    SAFETY_CHECK_ALREADY_CLOSED("SAFETY_002", "安否確認は既にクローズされています", Severity.WARN),

    /** 既に回答済み */
    ALREADY_RESPONDED("SAFETY_003", "既に安否確認に回答済みです", Severity.WARN),

    /** 回答ステータスが不正 */
    INVALID_RESPONSE_STATUS("SAFETY_004", "回答ステータスが不正です", Severity.ERROR),

    /** スコープ種別が不正 */
    INVALID_SCOPE_TYPE("SAFETY_005", "スコープ種別が不正です", Severity.ERROR),

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("SAFETY_006", "テンプレートが見つかりません", Severity.WARN),

    /** プリセットが見つからない */
    PRESET_NOT_FOUND("SAFETY_007", "プリセットが見つかりません", Severity.WARN),

    /** フォローアップが見つからない */
    FOLLOWUP_NOT_FOUND("SAFETY_008", "フォローアップが見つかりません", Severity.WARN),

    /** 回答が見つからない */
    RESPONSE_NOT_FOUND("SAFETY_009", "回答が見つかりません", Severity.WARN),

    /** アクセス権なし */
    ACCESS_DENIED("SAFETY_010", "この安否確認へのアクセス権がありません", Severity.WARN),

    /** リマインド送信間隔が短すぎる */
    REMIND_TOO_FREQUENT("SAFETY_011", "リマインド送信間隔が短すぎます", Severity.WARN),

    /** 一括回答数上限超過 */
    BULK_RESPOND_LIMIT_EXCEEDED("SAFETY_012", "一括回答は最大100件までです", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
