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

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 30)
    private final String icon;

    @Size(max = 7)
    private final String color;

    private final Boolean isParticipantRequired;

    private final String defaultVisibility;

    private final List<TemplateFieldInput> fields;

    /**
     * テンプレートフィールド入力。
     */
    @Getter
    @RequiredArgsConstructor
    public static class TemplateFieldInput {
        @NotBlank
        @Size(max = 50)
        private final String fieldKey;

        @NotBlank
        @Size(max = 100)
        private final String fieldLabel;

        @NotBlank
        private final String fieldType;

        private final Boolean isRequired;
        private final String optionsJson;

        @Size(max = 200)
        private final String placeholder;

        @Size(max = 20)
        private final String unit;

        private final Boolean isAggregatable;
        private final Integer sortOrder;
    }
}
