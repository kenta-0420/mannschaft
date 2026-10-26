package com.mannschaft.app.user;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ユーザー機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    /** ブロック済み */
    USER_001("USER_001", "既にブロック済みです", Severity.WARN),

    /** ブロックが見つからない */
    USER_002("USER_002", "ブロックが見つかりません", Severity.WARN),

    /** 自分自身はブロックできない */
    USER_003("USER_003", "自分自身をブロックすることはできません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
