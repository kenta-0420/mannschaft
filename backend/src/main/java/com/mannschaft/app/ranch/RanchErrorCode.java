package com.mannschaft.app.ranch;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 正本 03 の牧場エラー。個別 HTTP mapping は共通 registry の統合担当が登録する。 */
@Getter
@RequiredArgsConstructor
public enum RanchErrorCode implements ErrorCode {
    RANCH_001("RANCH_001", "対象が見つかりません", Severity.WARN),
    RANCH_002("RANCH_002", "残高が不足しています", Severity.WARN),
    RANCH_003("RANCH_003", "同じ操作キーを異なる入力に使用できません", Severity.WARN),
    RANCH_004("RANCH_004", "現在この機能を利用できません", Severity.WARN),
    RANCH_005("RANCH_005", "対象の置物が見つかりません", Severity.WARN),
    RANCH_006("RANCH_006", "入力値が不正です", Severity.WARN),
    RANCH_007("RANCH_007", "状態が変更されています", Severity.WARN),
    RANCH_008("RANCH_008", "牧場の状態を確認できません", Severity.ERROR),
    RANCH_009("RANCH_009", "牧場の操作を保存できません", Severity.ERROR),
    RANCH_010("RANCH_010", "操作が集中しています。しばらく待ってからお試しください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
