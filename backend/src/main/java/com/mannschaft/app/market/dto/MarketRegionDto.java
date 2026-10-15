package com.mannschaft.app.market.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F22.1 市: 公開札の地域情報（コード＋マスタ名・02_api_design §3.1）。
 */
@Getter
@AllArgsConstructor
public class MarketRegionDto {

    private final String prefectureCode;
    private final String prefectureName;
    private final String cityCode;
    private final String cityName;
}
