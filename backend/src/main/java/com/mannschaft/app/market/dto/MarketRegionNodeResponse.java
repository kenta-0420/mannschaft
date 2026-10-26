package com.mannschaft.app.market.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F22.1 市: 地域ファサード（{@code /regions}）の地域ノード（02_api_design §3.3）。
 *
 * <p>都道府県一覧では {@code prefectureCode} が null（自身が都道府県）、
 * 市区町村一覧では {@code prefectureCode} に親都道府県コードが入る。</p>
 */
@Getter
@AllArgsConstructor
public class MarketRegionNodeResponse {

    /** コード（都道府県 2 桁 / 市区町村 5 桁）。 */
    private final String code;

    /** 表示名（マスタの日本語名）。 */
    private final String name;

    /** 親都道府県コード（市区町村ノードのみ。都道府県ノードでは null）。 */
    private final String prefectureCode;
}
