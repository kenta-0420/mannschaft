package com.mannschaft.app.market.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F22.1 市: 公開札のジャンル情報（02_api_design §3.1）。
 *
 * <p>名称は i18n キー（{@code recruitment_categories.name_i18n_key}）を返し、表示文言は
 * フロントの i18n に委ねる。</p>
 */
@Getter
@AllArgsConstructor
public class MarketCategoryDto {

    private final Long id;

    /** カテゴリ名の i18n キー。 */
    private final String nameKey;
}
