package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * モデレーション対応テンプレート更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateModerationTemplateRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @NotBlank
    @Size(max = 20)
    private final String actionType;

    @Size(max = 30)
    private final String reason;

    @NotBlank
    private final String templateText;

    @Size(max = 10)
    private final String language;

    private final Boolean isDefault;
}
