package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * テンプレートからの指標一括作成レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FromTemplateResponse {

    private final int createdCount;
    private final List<MetricResponse> metrics;
}
