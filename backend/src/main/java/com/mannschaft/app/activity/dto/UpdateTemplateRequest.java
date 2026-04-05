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

    @Size(max = 30)
    private final String icon;

    @Size(max = 7)
    private final String color;

    private final Boolean isParticipantRequired;

    private final String defaultVisibility;

    private final List<CreateTemplateRequest.TemplateFieldInput> fields;
}
