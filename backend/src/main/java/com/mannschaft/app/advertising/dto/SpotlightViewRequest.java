package com.mannschaft.app.advertising.dto;

/**
 * F09.19.2 {@code POST /api/v1/spotlight/{creativeId}/view} のリクエスト（正本 §6.3）。
 *
 * @param placement           掲載面（必須。ads.placement と一致検証）
 * @param campaignId          運用型 ad_campaigns.id（運用型のとき。messagingCampaignId と排他）
 * @param messagingCampaignId F09.17 ad_messaging_campaigns.id（UUID 文字列。予約バナーのとき）
 * @param deliveryId          F09.17 ad_banner_deliveries.id（UUID 文字列。予約バナーのとき必須）
 */
public record SpotlightViewRequest(
        String placement,
        Long campaignId,
        String messagingCampaignId,
        String deliveryId) {
}
