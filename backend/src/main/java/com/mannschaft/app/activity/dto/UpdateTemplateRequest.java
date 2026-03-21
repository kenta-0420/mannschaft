package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 活動テンプレート更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTemplateRequest {

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

    private final List<CreateTemplateRequest.TemplateFieldInput> fields;
}
