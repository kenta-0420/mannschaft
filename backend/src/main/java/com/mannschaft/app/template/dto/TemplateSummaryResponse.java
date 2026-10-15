package com.mannschaft.app.template.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * テンプレートサマリーレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class TemplateSummaryResponse {

    private final Long id;
    private final String name;
    private final String slug;
    private final String category;
    private final int moduleCount;
}
