package com.mannschaft.app.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * プロジェクト横断で使用する共通エラーコード。
 * 各機能固有のエラーコードは機能パッケージ内に別 Enum として定義する。
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    /** 未認証エラー */
    COMMON_000("COMMON_000", "認証が必要です", Severity.WARN),

    /** バリデーションエラー */
    COMMON_001("COMMON_001", "入力内容に不備があります", Severity.WARN),

    /** 認可エラー */
    COMMON_002("COMMON_002", "この操作を行う権限がありません", Severity.WARN),

    /** 楽観ロック競合 */
    COMMON_003("COMMON_003",
            "他のユーザーがデータを更新しました。最新の内容を確認して再度お試しください",
            Severity.WARN),

    /** 予期しないシステムエラー */
    COMMON_999("COMMON_999", "システムエラーが発生しました", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
