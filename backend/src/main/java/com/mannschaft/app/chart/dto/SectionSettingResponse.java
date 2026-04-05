package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セクション設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SectionSettingResponse {

    private final String sectionType;
    private final Boolean isEnabled;
}
