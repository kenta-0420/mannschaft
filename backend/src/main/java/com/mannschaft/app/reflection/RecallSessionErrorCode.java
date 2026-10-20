package com.mannschaft.app.reflection;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 新想起セッションの版・命令競合を定型分類する。本文を文言へ混ぜない。 */
@Getter
@RequiredArgsConstructor
public enum RecallSessionErrorCode implements ErrorCode {
    STATE_CONFLICT("RECALLSESSION_001","想起セッションの状態が変更されています",Severity.WARN),
    COMMAND_CONFLICT("RECALLSESSION_002","同じ命令キーを別の操作に使用できません",Severity.WARN),
    UNAVAILABLE("RECALLSESSION_003","想起セッションを読み込めません",Severity.WARN);
    private final String code;
    private final String message;
    private final Severity severity;
}
