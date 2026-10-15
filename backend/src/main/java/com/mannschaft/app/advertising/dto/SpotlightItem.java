package com.mannschaft.app.advertising.dto;

/**
 * F09.19.2 サービング応答の 1 候補（正本 §6.2）。
 *
 * <p>source=HOUSE のとき house 非 null / affiliate null、source=AFFILIATE のとき affiliate 非 null / house null。</p>
 *
 * @param source    "HOUSE" | "AFFILIATE"
 * @param house     HOUSE 候補（source=HOUSE のとき非 null）
 * @param affiliate AFFILIATE 候補（source=AFFILIATE のとき非 null）
 */
public record SpotlightItem(
        String source,
        SpotlightHouseItem house,
        SpotlightAffiliateItem affiliate) {
}
