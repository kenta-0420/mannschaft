package com.mannschaft.app.pointcard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * F18 Phase 2 S2B — 自店プロバイダー新規発行リクエスト DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6 / §12 / §3.3 UC-8
 *
 * <p>{@code type} は常に {@code SELF_ISSUED_STAMP} として保存される（API 経由で変更不可）。
 * {@code organization_id} はパスパラメータ {@code orgId} から自動充填されるためリクエストには含めない。
 * {@code code} は {@code org_{orgId}_{rand8}} 形式でサーバー側自動生成する。
 *
 * @param displayName            表示名（例: 「サロン○○ ポイント」）。必須・100 文字以内
 * @param brandColor             ブランドカラー (#RRGGBB 形式)。任意
 * @param logoUrl                ロゴ画像 URL（R2 のオブジェクトキーまたは絶対 URL）。任意・500 文字以内
 * @param cardNumberRegex        カード番号バリデーション正規表現。任意・200 文字以内
 * @param cardNumberLengthHint   UI 表示用のカード番号桁数ヒント。任意・50 文字以内
 */
public record CreateOrgProviderRequest(
        @NotBlank
        @Size(max = 100)
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
