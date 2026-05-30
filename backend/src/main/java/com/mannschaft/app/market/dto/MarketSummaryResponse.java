package com.mannschaft.app.market.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * F22.1 市: 地域別の立っている札の件数（パンくず/集客用・02_api_design §3.4）。
 *
 * <p>PII を一切含まない（件数のみ）。</p>
 */
@Getter
@AllArgsConstructor
public class MarketSummaryResponse {

    private final List<RegionCount> byPrefecture;
    private final List<RegionCount> byCity;

    /**
     * 地域ノードごとの件数。
     */
    @Getter
    @AllArgsConstructor
    public static class RegionCount {
        private final String code;
        private final String name;
        private final long count;
    }
}
