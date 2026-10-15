package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F22.1 市: 札の地域情報ビュー（02_api_design §3.1 / §4 レスポンス）。
 *
 * <p>都道府県・市区町村のコードと表示名を持つ。地域未指定の札では全フィールド null。
 * 表示名は {@code prefectures}/{@code cities} マスタの日本語名（Phase 1 は日本語のみ・§04 §3-16）。</p>
 */
@Getter
@AllArgsConstructor
public class RecruitmentRegionView {

    private final String prefectureCode;
    private final String prefectureName;
    private final String cityCode;
    private final String cityName;
}
