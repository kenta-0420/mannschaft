package com.mannschaft.app.gdpr.dto;

/**
 * GDPR パージ手動 retry API のレスポンス DTO。
 *
 * <p>システム管理者が PENDING 状態のドメインパージを手動で再実行した結果を表す。</p>
 *
 * @param succeeded  retry が成功し、対象ドメインが SUCCESS に遷移したか
 * @param domainName retry 対象ドメイン（role / team / payment / chart / proxy / errorreport）
 * @param newStatus  retry 後のステータス（SUCCESS / PENDING）
 * @param retryCount retry 累計回数（今回の実行後の値）
 * @param message    結果の説明メッセージ（日本語）
 * @param queued     非同期再試行を受け付け、削除完了を待っているか（成功とは区別する）
 */
public record RetryResultResponse(
        boolean succeeded,
        String domainName,
        String newStatus,
        Integer retryCount,
        String message,
        boolean queued
) {
    /** 既存の同期ドメインの返却契約を維持する。 */
    public RetryResultResponse(boolean succeeded, String domainName, String newStatus,
                               Integer retryCount, String message) {
        this(succeeded, domainName, newStatus, retryCount, message, false);
    }
}
