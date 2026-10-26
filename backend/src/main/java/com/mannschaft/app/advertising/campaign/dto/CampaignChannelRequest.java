package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * F09.17 キャンペーンチャネル作成・更新リクエスト。
 *
 * <p>設計書 §4 {@code POST /api/v1/advertiser/campaigns/messaging/{id}/channels} のバリデーションに準拠。
 * Markdown サニタイズ・URL 許可ホスト照合などの追加検証は Service 層 / 後続フェーズで実装する。</p>
 */
public record CampaignChannelRequest(
        @NotNull
        AdChannelType channelType,

        @NotBlank
        @Size(max = 10)
        @Pattern(regexp = "ja|en|zh-CN|zh-TW|ko|pt-BR", message = "locale は ja/en/zh-CN/zh-TW/ko/pt-BR のいずれかを指定してください")
        String locale,

        @Size(max = 200)
        String subject,

        @NotBlank
        @Size(max = 8_000)
        String bodyMarkdown,

        @Size(max = 500)
        String imageUrl,

        @Size(max = 50)
        String ctaLabel,

        @Size(max = 500)
        String ctaUrl,

        /** BANNER 時のみ必須。Service 層でチェック。 */
        Long bannerCreativeId
) {
}
