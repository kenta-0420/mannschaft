package com.mannschaft.app.common.ranchsource;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 源配送管理だけの固定分類。本文・recipient・内部hashを応答へ含めない。 */
@Getter
@RequiredArgsConstructor
public enum SourceOutboxErrorCode implements ErrorCode {
    SOURCEOUTBOX_001("SOURCEOUTBOX_001","源配送管理を現在利用できません",Severity.WARN),
    SOURCEOUTBOX_002("SOURCEOUTBOX_002","対象の配送行が見つかりません",Severity.WARN),
    SOURCEOUTBOX_003("SOURCEOUTBOX_003","同じ命令キーに異なる入力は使用できません",Severity.WARN),
    SOURCEOUTBOX_004("SOURCEOUTBOX_004","対象の配送行は現在処理中です",Severity.WARN);
    private final String code;
    private final String message;
    private final Severity severity;
}
