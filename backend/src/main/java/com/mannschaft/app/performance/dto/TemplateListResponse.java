package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 指標テンプレート一覧レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TemplateListResponse {

    private final List<String> categories;
    private final List<CategoryTemplates> templates;

    @Getter
    @RequiredArgsConstructor
    public static class CategoryTemplates {
        private final String sportCategory;
        private final List<TemplateMetric> metrics;
    }

    @Getter
    @RequiredArgsConstructor
    public static class TemplateMetric {
        private final Long id;
        private final String name;
        private final String unit;
        private final String dataType;
        private final String aggregationType;
        private final String groupName;
        private final String description;
        private final BigDecimal minValue;
        private final BigDecimal maxValue;
        private final Boolean isSelfRecordable;
    }
}
