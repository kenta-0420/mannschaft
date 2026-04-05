package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * テンプレート複製リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class DuplicateTemplateRequest {

    @NotBlank
    private final String targetScopeType;

    @NotNull
    private final Long targetScopeId;
}
