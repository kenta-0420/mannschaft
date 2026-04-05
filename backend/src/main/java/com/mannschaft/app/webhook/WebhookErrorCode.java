package com.mannschaft.app.webhook;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Webhook/外部API連携機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum WebhookErrorCode implements ErrorCode {

    /** Webhookエンドポイントが見つかりません */
    WEBHOOK_001("WEBHOOK_001", "Webhookエンドポイントが見つかりません", Severity.WARN),

    /** URLはHTTPSである必要があります */
    WEBHOOK_002("WEBHOOK_002", "URLはHTTPSである必要があります", Severity.WARN),

    /** プライベートIPアドレスへの接続は許可されていません（SSRF防止） */
    WEBHOOK_003("WEBHOOK_003", "プライベートIPアドレスへの接続は許可されていません", Severity.WARN),

    /** エンドポイント数の上限（10件）に達しました */
    WEBHOOK_004("WEBHOOK_004", "エンドポイント数の上限（10件）に達しました", Severity.WARN),

    /** Incomingトークンが見つかりません/無効です */
    WEBHOOK_005("WEBHOOK_005", "Incomingトークンが見つかりません、または無効です", Severity.WARN),

    /** Incomingトークン数の上限（5件）に達しました */
    WEBHOOK_006("WEBHOOK_006", "Incomingトークン数の上限（5件）に達しました", Severity.WARN),

    /** APIキーが見つかりません/無効です */
    WEBHOOK_007("WEBHOOK_007", "APIキーが見つかりません、または無効です", Severity.WARN),

    /** APIキー数の上限（5件）に達しました */
    WEBHOOK_008("WEBHOOK_008", "APIキー数の上限（5件）に達しました", Severity.WARN),

    /** このAPIキーは読み取り専用です */
    WEBHOOK_009("WEBHOOK_009", "このAPIキーは読み取り専用です", Severity.WARN),

    /** バージョンが一致しません */
    WEBHOOK_010("WEBHOOK_010", "バージョンが一致しません", Severity.WARN),

    /** APIキーの有効期限が切れています */
    WEBHOOK_011("WEBHOOK_011", "APIキーの有効期限が切れています", Severity.WARN),

    /** レートリミットを超過しました */
    WEBHOOK_012("WEBHOOK_012", "レートリミットを超過しました", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
