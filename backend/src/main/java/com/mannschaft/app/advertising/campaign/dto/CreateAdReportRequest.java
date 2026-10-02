package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdReportReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * F09.19.9 通報作成リクエスト（{@code POST /api/v1/me/ad-reports}）。
 *
 * <p>{@code campaignId}（メッセージ型）と {@code operationalCampaignId}（運用型）は XOR。
 * 両方指定・両方 null はいずれも AD_032（400）。この XOR は Service 層で検証する
 * （Bean Validation では相互排他を素直に書けないため）。</p>
 *
 * @param campaignId            メッセージ型キャンペーン（ad_messaging_campaigns.id・UUID）。運用型時 null
 * @param operationalCampaignId 運用型キャンペーン（ad_campaigns.id・Long）。メッセージ型時 null
 * @param channelType           通報元チャネル（運用型は常に BANNER）
 * @param reasonCode            通報理由（OFFENSIVE / MISLEADING / SPAM / IRRELEVANT / OTHER）
 * @param comment               自由記述（null 可・500 文字以内）
 */
public record CreateAdReportRequest(
        UUID campaignId,

        Long operationalCampaignId,

        @NotNull(message = "通報元チャネルは必須です")
        AdChannelType channelType,

        @NotNull(message = "通報理由は必須です")
        AdReportReasonCode reasonCode,

        @Size(max = 500, message = "コメントは 500 文字以内で入力してください")
        String comment
) {
}
