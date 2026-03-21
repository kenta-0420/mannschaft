package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 活動テンプレート作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTemplateRequest {

    private final Long teamId;
    private final Long organizationId;

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 50)
    private final String icon;

    @Size(max = 7)
    private final String color;

    @Size(max = 200)
    private final String defaultTitlePattern;

    private final String defaultVisibility;

    @Size(max = 200)
    private final String defaultLocation;

    private final List<TemplateFieldInput> fields;

    /**
     * テンプレートフィールド入力。
     */
    @Getter
    @RequiredArgsConstructor
    public static class TemplateFieldInput {
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
