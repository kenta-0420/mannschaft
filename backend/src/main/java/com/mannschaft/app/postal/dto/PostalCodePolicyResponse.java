package com.mannschaft.app.postal.dto;

import com.mannschaft.app.postal.PostalCodePolicy;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 郵便番号検証ポリシーの公開レスポンス DTO。
 *
 * <p>{@code GET /api/v1/postal-code/policies} が対応国ごとに返す。フロントエンドは
 * これを単一の真実源として、対応国の判定・フォーマット検証・入力例表示に用いる。</p>
 *
 * <p>設計書: F02.10 §391（郵便番号検証基盤）。</p>
 *
 * @param countryCode ISO 3166-1 alpha-2 国コード
 * @param pattern     郵便番号フォーマットの正規表現
 * @param example     入力例
 */
@Schema(name = "PostalCodePolicyResponse", description = "国別郵便番号検証ポリシー")
public record PostalCodePolicyResponse(
        @Schema(description = "ISO 3166-1 alpha-2 国コード", example = "JP")
        String countryCode,
        @Schema(description = "郵便番号フォーマットの正規表現", example = "^\\d{3}-?\\d{4}$")
        String pattern,
        @Schema(description = "入力例", example = "123-4567")
        String example) {

    /**
     * ドメインモデルから DTO を生成する。
     *
     * @param policy ポリシー
     * @return レスポンス DTO
     */
    public static PostalCodePolicyResponse from(PostalCodePolicy policy) {
        return new PostalCodePolicyResponse(
                policy.countryCode(), policy.pattern(), policy.example());
    }
}
