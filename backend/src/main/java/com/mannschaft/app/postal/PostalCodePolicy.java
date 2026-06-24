package com.mannschaft.app.postal;

/**
 * 国別の郵便番号検証ポリシー。
 *
 * <p>「対応国（郵便番号必須・フォーマット検証あり）」1 件分の規則を表す不変レコード。
 * 単一の真実源 {@link PostalCodePolicyRegistry} が国コードをキーに保持し、
 * 登録・プロフィール更新の検証および公開 API（{@code GET /api/v1/postal-code/policies}）で
 * 同じ規則を参照する。</p>
 *
 * <p>設計書: F02.10 §391（郵便番号検証基盤）。</p>
 *
 * @param countryCode ISO 3166-1 alpha-2 国コード（大文字。例: {@code "JP"}）
 * @param pattern     生入力に対して評価する正規表現（例: {@code "^\\d{3}-?\\d{4}$"}）
 * @param example     UI 表示用の入力例（例: {@code "123-4567"}）
 */
public record PostalCodePolicy(
        String countryCode,
        String pattern,
        String example) {
}
