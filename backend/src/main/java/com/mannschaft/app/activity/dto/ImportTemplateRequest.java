package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * プリセットテンプレートインポートリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ImportTemplateRequest {

    @NotNull
    private final Long presetId;

    @NotBlank
    private final String scopeType;

    @NotNull
    private final Long scopeId;
}
