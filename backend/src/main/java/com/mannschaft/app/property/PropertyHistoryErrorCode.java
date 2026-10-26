package com.mannschaft.app.property;

import com.mannschaft.app.common.ErrorCode;

/**
 * 物件履歴台帳（F09.13）機能のエラーコード。
 *
 * <p>設計書 {@code docs/features/F09.13_property_history.md} §4 「エラーコード」表に対応。
 * HTTP ステータスは {@link com.mannschaft.app.common.GlobalExceptionHandler} 側で
 * {@link Severity} に基づきマッピングされる（既存規約踏襲）。各 ErrorCode の意味的な
 * HTTP ステータスは設計書の表で明記しているが、本 enum 自体は文字列コード・メッセージ・
 * Severity のみを持ち、HttpStatus は持たない（{@link IncidentErrorCode} と同パターン）。</p>
 */
public enum PropertyHistoryErrorCode implements ErrorCode {

    /** 設計書 §4: 404 — パッケージが見つからない。 */
    PROPERTY_001("PROPERTY_001", "物件履歴パッケージが見つかりません", Severity.WARN),

    /** 設計書 §4: 403 — 閲覧権限なし。 */
    PROPERTY_002("PROPERTY_002", "物件履歴パッケージの閲覧権限がありません", Severity.WARN),

    /** 設計書 §4: 409 — バージョン競合（楽観的ロック）。 */
    PROPERTY_003("PROPERTY_003", "他のユーザーが先に更新しました。最新版を取得してから再実行してください",
            Severity.WARN),

    /** 設計書 §4: 400 — 入力バリデーション違反。 */
    PROPERTY_004("PROPERTY_004", "入力内容に誤りがあります", Severity.WARN),

    /** 設計書 §4: 404 — 業者が見つからない。 */
    PROPERTY_005("PROPERTY_005", "業者が見つかりません", Severity.WARN),

    /** 設計書 §4: 409 — 業者名重複。 */
    PROPERTY_006("PROPERTY_006", "同じ名称の業者が既に登録されています", Severity.WARN),

    /** 設計書 §4: 422 — F08.6 BudgetTransaction 連携エラー。 */
    PROPERTY_007("PROPERTY_007", "予算取引との連携に失敗しました", Severity.WARN),

    /** 設計書 §4: 422 — F05.5 SharedFile が他スコープで紐付け不可。 */
    PROPERTY_008("PROPERTY_008", "他スコープのファイルは紐付けできません", Severity.WARN),

    /** 設計書 §4: 413 — 添付ファイル数上限超過（パッケージあたり50件）。 */
    PROPERTY_009("PROPERTY_009", "添付ファイル数の上限（50件）を超えました", Severity.WARN),

    /** 設計書 §4: 429 — エクスポート頻度制限（1分10回）。 */
    PROPERTY_010("PROPERTY_010", "エクスポートの頻度制限を超えました。しばらく時間をおいて再実行してください",
            Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;

    PropertyHistoryErrorCode(String code, String message, Severity severity) {
        this.code = code;
        this.message = message;
        this.severity = severity;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public Severity getSeverity() {
        return severity;
    }
}
