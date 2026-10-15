package com.mannschaft.app.template.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * モジュールサマリーレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class ModuleSummaryResponse {

    private final Long id;
    private final String name;
    private final String slug;
    private final String moduleType;
}
