package com.mannschaft.app.template.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

/** ひとつの一般機能の独立したロール別表示設定。欠落boolをfalseと区別する。 */
public record FeatureDisplayPolicyItem(
        @NotBlank @Size(max = 64) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String featureKey,
        @NotNull Boolean memberVisible,
        @NotNull Boolean supporterVisible) { }
