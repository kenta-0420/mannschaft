package com.mannschaft.app.mention;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メンション機能固有のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum MentionErrorCode implements ErrorCode {

    /** メンションが見つからない */
    MENTION_NOT_FOUND("MENTION_001", "メンションが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
