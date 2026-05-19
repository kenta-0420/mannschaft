package com.mannschaft.app.publicview.dto;

/**
 * 段階開示済みの投稿者識別情報（公開 DTO）。
 *
 * <p>F19.1 §6.3 / §10.4 「PII 完全分離」原則に従う公開専用 DTO。
 * {@link com.mannschaft.app.publicview.visibility.IdentityVisibilityResolver} の
 * 出力 {@link com.mannschaft.app.publicview.visibility.DisplayIdentity} を
 * 公開エンドポイントレスポンスに詰めるための DTO。</p>
 *
 * <p><strong>禁則フィールド（型レベルで含めない）</strong>:</p>
 * <ul>
 *   <li>{@code userId} / {@code authorId}（個人特定可能）</li>
 *   <li>{@code firstName} / {@code lastName} / {@code email} / {@code phone} / {@code birthday}</li>
 *   <li>本名スナップショット（Phase 1 では Resolver 出力に含まれないため自然に除外される）</li>
 * </ul>
 *
 * @param displayLabel             表示する投稿者名（汎用ラベル または display_name フォールバック解決済み）
 * @param avatarUrl                表示アバター URL（汎用アバター or 実アバター）
 * @param teamAffiliationVisible   「このメンバーは XX チームに所属しています」表示の許可フラグ
 * @param isAnonymized             汎用ラベルにフォールバック済みか（未ログイン / 非メンバー時 true）
 */
public record PublicAuthorIdentity(
        String displayLabel,
        String avatarUrl,
        boolean teamAffiliationVisible,
        boolean isAnonymized
) {
}
