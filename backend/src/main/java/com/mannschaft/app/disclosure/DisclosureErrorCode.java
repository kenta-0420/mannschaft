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

    /**
     * 権限なし。設計書 §4 の表記は 403 だが、実装では他スコープの ID を指定した場合の
     * 存在秘匿に本コードを使うため、{@code GlobalExceptionHandler} で 404 に正規化している
     * （不在の {@code DISCLOSURE_001} と同一応答に畳む）。
     */
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
            Severity.ERROR),

    /**
     * 設計書 §5.7: 422 — 自動削除予定日（{@code expires_at}）の延長範囲違反。
     * 過去日時、または本日から 7 年超を指定した場合に投げる。
     */
    DISCLOSURE_011("DISCLOSURE_011",
            "自動削除予定日は現在時刻より未来かつ本日から最大7年以内である必要があります",
            Severity.WARN),

    /**
     * 設計書 §3 disclosure_form_templates: 422 — 組織あたりカスタム様式件数上限超過（10 件）。
     * Phase 3-C で追加。CHECK 制約ではなく Service 層で計数してから保存前に弾く。
     * <p>※ 011/012 は Phase 3-E（自動削除バッチ・期限延長）が先行採番したため 013/014 に振り直し。</p>
     */
    DISCLOSURE_013("DISCLOSURE_013", "カスタム様式の登録件数が上限（10件）を超えています", Severity.WARN),

    /**
     * 403 — システム提供（{@code is_system_template=true}）テンプレートに対する更新／削除操作。
     * Phase 3-C で追加。これらは {@code disclosure_form_templates} シードで投入されるため
     * ユーザーは編集不可。
     */
    DISCLOSURE_014("DISCLOSURE_014", "システム提供の様式は編集／削除できません", Severity.WARN);

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
