package com.mannschaft.app.advertising.dto;

/**
 * F09.19.2 {@code POST /api/v1/spotlight/{creativeId}/visit} のリクエスト（正本 §6.4）。
 *
 * @param placement           掲載面（必須。ads.placement と一致検証）
 * @param impressionId        view 済みなら紐付ける ad_impressions.id（未 view クリックは null 許容）
 * @param campaignId          運用型 ad_campaigns.id（運用型のとき）
 * @param messagingCampaignId F09.17 ad_messaging_campaigns.id（UUID 文字列。予約バナーのとき）
 * @param deliveryId          F09.17 ad_banner_deliveries.id（UUID 文字列。予約バナーのとき必須）
 */
public record SpotlightVisitRequest(
        String placement,
        Long impressionId,
        Long campaignId,
        String messagingCampaignId,
        String deliveryId) {
}
