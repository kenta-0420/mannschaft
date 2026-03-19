package com.mannschaft.app.template;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.3 テンプレート・モジュール管理機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum TemplateErrorCode implements ErrorCode {

    /** テンプレートが見つかりません */
    TMPL_001("TMPL_001", "テンプレートが見つかりません", Severity.WARN),

    /** モジュールが見つかりません */
    TMPL_002("TMPL_002", "モジュールが見つかりません", Severity.WARN),

    /** 無料プランでの選択式モジュール上限（10個）に達しています */
    TMPL_003("TMPL_003", "無料プランでの選択式モジュール上限（10個）に達しています", Severity.WARN),

    /** このモジュールは有料プランが必要です */
    TMPL_004("TMPL_004", "このモジュールは有料プランが必要です", Severity.WARN),

    /** このレベルではモジュールを利用できません */
    TMPL_005("TMPL_005", "このレベルではモジュールを利用できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
