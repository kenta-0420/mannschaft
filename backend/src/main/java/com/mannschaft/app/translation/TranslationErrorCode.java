package com.mannschaft.app.translation;

import com.mannschaft.app.common.ErrorCode;

/**
 * 多言語翻訳機能のエラーコード。
 */
public enum TranslationErrorCode implements ErrorCode {

    TRANSLATION_001("TRANSLATION_001", "翻訳設定が見つかりません", Severity.WARN),
    TRANSLATION_002("TRANSLATION_002", "翻訳コンテンツが見つかりません", Severity.WARN),
    TRANSLATION_003("TRANSLATION_003", "この言語は有効化されていません", Severity.WARN),
    TRANSLATION_004("TRANSLATION_004", "同一原文・同一言語の翻訳が既に存在します", Severity.WARN),
    TRANSLATION_005("TRANSLATION_005", "このステータスへの遷移は許可されていません", Severity.WARN),
    TRANSLATION_006("TRANSLATION_006", "アクセス権限がありません", Severity.WARN),
    TRANSLATION_007("TRANSLATION_007", "バージョンが一致しません", Severity.WARN),
    TRANSLATION_008("TRANSLATION_008", "この言語のアサインがありません", Severity.WARN),
    TRANSLATION_009("TRANSLATION_009", "翻訳担当者アサインが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;

    TranslationErrorCode(String code, String message, Severity severity) {
        this.code = code;
        this.message = message;
        this.severity = severity;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public Severity getSeverity() {
        return severity;
    }
}
