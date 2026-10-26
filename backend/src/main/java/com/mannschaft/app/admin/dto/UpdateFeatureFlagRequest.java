package com.mannschaft.app.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フィーチャーフラグ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateFeatureFlagRequest {

    @NotNull
    private final Boolean isEnabled;

    private final String description;
}
