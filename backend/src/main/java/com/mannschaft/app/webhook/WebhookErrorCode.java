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

    /** Webhookエンドポイントが見つかりません（404） */
    WEBHOOK_001("WEBHOOK_001", "Webhookエンドポイントが見つかりません", Severity.WARN),

    /** URLはHTTPSである必要があります */
    WEBHOOK_002("WEBHOOK_002", "URLはHTTPSである必要があります", Severity.WARN),

    /** プライベートIPアドレスへの接続は許可されていません（SSRF防止） */
    WEBHOOK_003("WEBHOOK_003", "プライベートIPアドレスへの接続は許可されていません", Severity.WARN),

    /** エンドポイント数の上限（10件）に達しました */
    WEBHOOK_004("WEBHOOK_004", "エンドポイント数の上限（10件）に達しました", Severity.WARN),

    /**
     * Incoming Webhook の受信時トークン認証に失敗した（401）。
     *
     * <p>かつては受信時の認証失敗と管理系CRUDでのトークン不在の2意味で共用されていたが分割した。
     * 本コードは<b>受信エンドポイントでの認証失敗</b>専用。管理系CRUDでの不在は
     * {@link #WEBHOOK_013}（404）を使うこと。</p>
     */
    WEBHOOK_005("WEBHOOK_005", "Incomingトークンが見つかりません、または無効です", Severity.WARN),

    /** Incomingトークン数の上限（5件）に達しました */
    WEBHOOK_006("WEBHOOK_006", "Incomingトークン数の上限（5件）に達しました", Severity.WARN),

    /**
     * APIキーの認証に失敗した（401）。
     *
     * <p><b>「キーの形式が不正」と「照合不一致（値が違う）」を意図的に同一コードへ畳んでいる。
     * この2つを分割してはならない。</b>分ければ「形式は合っているが値が違う」ことを応答から
     * 判別でき、総当たりの手掛かりを与えてしまう。どちらも単に認証の失敗として 401 を返す。</p>
     *
     * <p>管理系CRUDでのAPIキー不在は認証ではなくリソース参照なので {@link #WEBHOOK_014}（404）
     * に分離済み。</p>
     */
    WEBHOOK_007("WEBHOOK_007", "APIキーが見つかりません、または無効です", Severity.WARN),

    /** APIキー数の上限（5件）に達しました */
    WEBHOOK_008("WEBHOOK_008", "APIキー数の上限（5件）に達しました", Severity.WARN),

    /** このAPIキーは読み取り専用です */
    WEBHOOK_009("WEBHOOK_009", "このAPIキーは読み取り専用です", Severity.WARN),

    /** バージョンが一致しません */
    WEBHOOK_010("WEBHOOK_010", "バージョンが一致しません", Severity.WARN),

    /** APIキーの有効期限が切れています（認証失敗のため401） */
    WEBHOOK_011("WEBHOOK_011", "APIキーの有効期限が切れています", Severity.WARN),

    /** レートリミットを超過しました */
    WEBHOOK_012("WEBHOOK_012", "レートリミットを超過しました", Severity.WARN),

    /** 管理系CRUDで対象の Incoming トークンが存在しない（404） */
    WEBHOOK_013("WEBHOOK_013", "Incomingトークンが見つかりません", Severity.WARN),

    /** 管理系CRUDで対象のAPIキーが存在しない（404） */
    WEBHOOK_014("WEBHOOK_014", "APIキーが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
