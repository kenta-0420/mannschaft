package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * プリセットテンプレート作成リクエストDTO（SYSTEM_ADMIN用）。
 */
@Getter
@RequiredArgsConstructor
public class CreatePresetRequest {

    @NotBlank
    private final String category;

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

    @NotNull
    private final String fieldsJson;
}
