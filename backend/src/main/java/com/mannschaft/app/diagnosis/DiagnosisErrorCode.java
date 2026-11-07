package com.mannschaft.app.diagnosis;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 本人診断の入力・所有・版競合を表すエラー。 */
@Getter @RequiredArgsConstructor
public enum DiagnosisErrorCode implements ErrorCode {
    /** HTTP 400。 */
    INVALID_INPUT("DIAGNOSIS_001", "入力内容に不備があります", Severity.WARN),

    /** HTTP 404。 */
    NOT_FOUND("DIAGNOSIS_002", "診断が見つかりません", Severity.WARN),

    /** HTTP 409。 */
    STATE_CONFLICT("DIAGNOSIS_003", "診断の状態が変更されています", Severity.WARN),

    /** HTTP 409。 */
    COMMAND_CONFLICT("DIAGNOSIS_004", "同じ命令キーを別の操作に使用できません", Severity.WARN),

    /** HTTP 503。 */
    UNAVAILABLE("DIAGNOSIS_005", "診断は準備中です", Severity.WARN),

    /** HTTP 400。 */
    INVALID_CURSOR("DIAGNOSIS_006", "一覧の続きの指定が無効です", Severity.WARN);
    private final String code;
    private final String message;
    private final Severity severity;
}
