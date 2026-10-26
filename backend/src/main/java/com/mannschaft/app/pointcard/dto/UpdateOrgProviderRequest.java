package com.mannschaft.app.pointcard.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * F18 Phase 2 S2B — 自店プロバイダー編集リクエスト DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6 / §12
 *
 * <p>全フィールド任意（null は変更なし）。{@code type} / {@code organization_id} / {@code code} は
 * 不変のためリクエストに含めない（含めても無視される設計）。
 *
 * @param displayName            表示名。null 不可で送信されたら {@link jakarta.validation.constraints.Size} のみ適用
 * @param brandColor             ブランドカラー (#RRGGBB 形式)
 * @param logoUrl                ロゴ画像 URL（R2 のオブジェクトキーまたは絶対 URL）
 * @param cardNumberRegex        カード番号バリデーション正規表現
 * @param cardNumberLengthHint   UI 表示用のカード番号桁数ヒント
 */
public record UpdateOrgProviderRequest(
        @Size(min = 1, max = 100)
        String displayName,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$",
                message = "brandColor は #RRGGBB 形式で指定してください")
        String brandColor,

        @Size(max = 500)
        String logoUrl,

        @Size(max = 200)
        String cardNumberRegex,

        @Size(max = 50)
        String cardNumberLengthHint
) {
}
