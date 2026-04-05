package com.mannschaft.app.common;

/**
 * エラーコード共通インターフェース。
 * 各機能モジュールは独自の ErrorCode Enum でこのインターフェースを実装する。
 */
public interface ErrorCode {

    String getCode();

    String getMessage();

    Severity getSeverity();

    /**
     * エラーの深刻度。HttpStatus へのデフォルトマッピングに使用される。
     */
    enum Severity {
        ERROR,
        WARN,
        INFO
    }
}
