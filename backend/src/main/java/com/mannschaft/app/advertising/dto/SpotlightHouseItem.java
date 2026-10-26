package com.mannschaft.app.advertising.dto;

/**
 * F09.19.2 サービング応答の自社（HOUSE）広告候補（正本 §6.2）。
 *
 * <p>運用型（campaignId 非 null）と F09.17 予約バナー（messagingCampaignId / deliveryId 非 null）の
 * 両方をこの 1 レコードで表現する。露出する内部 id は認証必須 API のため creativeId / campaignId /
 * advertiserAccountId のみ。</p>
 *
 * @param creativeId          ads.id（view/visit のパスパラメータに使う）
 * @param campaignId          運用型 ad_campaigns.id（運用型のとき非 null）
 * @param messagingCampaignId F09.17 ad_messaging_campaigns.id（UUID 文字列。予約バナーのとき非 null）
 * @param deliveryId          F09.17 ad_banner_deliveries.id（UUID 文字列。予約バナーのとき非 null）
 * @param advertiserAccountId advertiser_accounts.id（「この広告主を非表示」用）
 * @param advertiserName      広告主表示名（advertiser_accounts.company_name）
 * @param title               クリエイティブ表題
 * @param imageUrl            バナー画像 URL（null 時 FE はタイトルテキストカード）
 * @param destinationUrl      遷移先 URL
 * @param width               バナー幅 px（null 可）
 * @param height              バナー高さ px（null 可）
 * @param altText             代替テキスト（null 可）
 */
public record SpotlightHouseItem(
        Long creativeId,
        Long campaignId,
        String messagingCampaignId,
        String deliveryId,
        Long advertiserAccountId,
        String advertiserName,
        String title,
        String imageUrl,
        String destinationUrl,
        Integer width,
        Integer height,
        String altText) {
}
