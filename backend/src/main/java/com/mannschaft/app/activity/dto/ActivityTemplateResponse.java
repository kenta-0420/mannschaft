package com.mannschaft.app.activity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活動テンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ActivityTemplateResponse {

    private final Long id;
    private final String scopeType;
    private final Long teamId;
    private final Long organizationId;
    private final String name;
    private final String description;
    private final String icon;
    private final String color;
    private final String defaultTitlePattern;
    private final String defaultVisibility;
    private final String defaultLocation;
    private final String shareCode;
    private final Boolean isShared;
    private final Boolean isOfficial;
    private final Integer useCount;
    private final Integer importCount;
    private final List<TemplateFieldResponse> fields;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /**
     * テンプレートフィールドレスポンス。
     */
    @Getter
    @RequiredArgsConstructor
    public static class TemplateFieldResponse {
        private final Long id;
        private final String scope;
        private final String fieldName;
        private final String fieldType;
        private final String options;
        private final String unit;
        private final Boolean isRequired;
        private final String defaultValue;
        private final Integer sortOrder;
    }
}
