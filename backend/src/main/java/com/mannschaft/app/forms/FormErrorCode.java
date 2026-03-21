package com.mannschaft.app.forms;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F05.7 書類テンプレート・フォームビルダーのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum FormErrorCode implements ErrorCode {

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("FORM_001", "フォームテンプレートが見つかりません", Severity.WARN),

    /** 提出が見つからない */
    SUBMISSION_NOT_FOUND("FORM_002", "フォーム提出が見つかりません", Severity.WARN),

    /** プリセットが見つからない */
    PRESET_NOT_FOUND("FORM_003", "フォームプリセットが見つかりません", Severity.WARN),

    /** テンプレートステータス不正 */
    INVALID_TEMPLATE_STATUS("FORM_004", "この操作は現在のテンプレートステータスでは実行できません", Severity.WARN),

    /** 提出ステータス不正 */
    INVALID_SUBMISSION_STATUS("FORM_005", "この操作は現在の提出ステータスでは実行できません", Severity.WARN),

    /** テンプレートが公開済みでないため提出不可 */
    TEMPLATE_NOT_PUBLISHED("FORM_006", "テンプレートが公開されていないため提出できません", Severity.WARN),

    /** 提出回数上限超過 */
    MAX_SUBMISSIONS_EXCEEDED("FORM_007", "提出回数の上限に達しています", Severity.WARN),

    /** 提出後の編集不可 */
    EDIT_AFTER_SUBMIT_NOT_ALLOWED("FORM_008", "提出後の編集は許可されていません", Severity.WARN),

    /** フィールド定義が空 */
    EMPTY_FIELDS("FORM_009", "フィールドが1つ以上必要です", Severity.WARN),

    /** 必須フィールドの値が未入力 */
    REQUIRED_FIELD_MISSING("FORM_010", "必須フィールドの値が入力されていません", Severity.WARN),

    /** フィールドキー重複 */
    DUPLICATE_FIELD_KEY("FORM_011", "フィールドキーが重複しています", Severity.WARN),

    /** テンプレートの締切超過 */
    TEMPLATE_DEADLINE_PASSED("FORM_012", "テンプレートの締切を過ぎています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
