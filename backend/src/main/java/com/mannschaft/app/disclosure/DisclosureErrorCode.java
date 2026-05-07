package com.mannschaft.app.disclosure;

import com.mannschaft.app.common.ErrorCode;

/**
 * 重要事項説明書出力（F09.14）機能のエラーコード。
 *
 * <p>設計書 {@code docs/features/F09.14_real_estate_disclosure.md} §4 「エラーコード」表に対応。
 * HTTP ステータスは {@link com.mannschaft.app.common.GlobalExceptionHandler} 側で
 * {@link Severity} に基づきマッピングされる（既存規約踏襲）。各 ErrorCode の意味的な
 * HTTP ステータスは設計書の表で明記しているが、本 enum 自体は文字列コード・メッセージ・
 * Severity のみを持ち、HttpStatus は持たない（{@link com.mannschaft.app.property.PropertyHistoryErrorCode}
 * と同パターン）。</p>
 */
public enum DisclosureErrorCode implements ErrorCode {

    /** 設計書 §4: 404 — テンプレート/ドラフト/出力履歴が見つからない。 */
    DISCLOSURE_001("DISCLOSURE_001", "対象の重要事項説明書リソースが見つかりません", Severity.WARN),

    /** 設計書 §4: 403 — 権限なし。 */
    DISCLOSURE_002("DISCLOSURE_002", "重要事項説明書の操作権限がありません", Severity.WARN),

    /** 設計書 §4: 409 — バージョン競合（楽観的ロック）。 */
    DISCLOSURE_003("DISCLOSURE_003", "他のユーザーが先に更新しました。最新版を取得してから再実行してください",
            Severity.WARN),

    /** 設計書 §4: 400 — 入力バリデーション違反。 */
    DISCLOSURE_004("DISCLOSURE_004", "入力内容に誤りがあります", Severity.WARN),

    /** 設計書 §4: 412 — property_history モジュール未有効。 */
    DISCLOSURE_005("DISCLOSURE_005",
            "重要事項説明書を利用するには物件履歴台帳（property_history）モジュールの有効化が必要です",
            Severity.WARN),

    /** 設計書 §4: 422 — 様式の effective_until 経過、最新版に切替必要。 */
    DISCLOSURE_006("DISCLOSURE_006", "選択された様式は有効期限を過ぎています。最新版に切り替えてください",
            Severity.WARN),

    /** 設計書 §4: 422 — 必須項目未入力（form_schema.required = true）。 */
    DISCLOSURE_007("DISCLOSURE_007", "必須項目が未入力のため出力できません", Severity.WARN),

    /** 設計書 §4: 422 — 自動引用エラー（参照先データが見つからない）。 */
    DISCLOSURE_008("DISCLOSURE_008", "自動引用元のデータが取得できませんでした", Severity.WARN),

    /** 設計書 §4: 429 — エクスポート頻度制限（1分5回）。 */
    DISCLOSURE_009("DISCLOSURE_009",
            "エクスポートの頻度制限（1分5回）を超えました。しばらく時間をおいて再実行してください",
            Severity.WARN),

    /** 設計書 §4: 503 — PDF/Excel/Word 生成サービス一時障害。 */
    DISCLOSURE_010("DISCLOSURE_010", "ファイル生成サービスが一時的に利用できません。再度お試しください",
            Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;

    DisclosureErrorCode(String code, String message, Severity severity) {
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
