package com.mannschaft.app.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * アクションテンプレート作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateActionTemplateRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @NotBlank
    private final String actionType;

    private final String reason;

    @NotBlank
    private final String templateText;

    private final Boolean isDefault;
}
