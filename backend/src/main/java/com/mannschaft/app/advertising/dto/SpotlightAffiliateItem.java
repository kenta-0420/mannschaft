package com.mannschaft.app.advertising.dto;

/**
 * F09.19.2 サービング応答のアフィリエイト広告候補（正本 §6.2）。
 *
 * @param provider       "AMAZON" | "RAKUTEN"
 * @param affiliateUrl   BE が tag_id から構築した URL（FE は tag_id を扱わない）
 * @param bannerImageUrl バナー画像 URL（null 時 FE はプロバイダ別デフォルト描画）
 * @param width          バナー幅 px（null 可）
 * @param height         バナー高さ px（null 可）
 * @param altText        代替テキスト（null 可）
 */
public record SpotlightAffiliateItem(
        String provider,
        String affiliateUrl,
        String bannerImageUrl,
        Integer width,
        Integer height,
        String altText) {
}
