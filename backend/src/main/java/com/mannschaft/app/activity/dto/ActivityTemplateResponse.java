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
    private final Long scopeId;
    private final String name;
    private final String description;
    private final String icon;
    private final String color;
    private final Boolean isParticipantRequired;
    private final String defaultVisibility;
    private final Integer sortOrder;
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
        private final String fieldKey;
        private final String fieldLabel;
        private final String fieldType;
        private final Boolean isRequired;
        private final String optionsJson;
        private final String placeholder;
        private final String unit;
        private final Boolean isAggregatable;
        private final Integer sortOrder;
    }
}
