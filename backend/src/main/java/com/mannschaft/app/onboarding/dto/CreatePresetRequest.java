package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingPresetCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * システムプリセット作成リクエスト。
 */
public record CreatePresetRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotNull OnboardingPresetCategory category,
        @Size(max = 5000) String welcomeMessage,
        Boolean isOrderEnforced,
        Integer deadlineDays,
        String stepsJson
) {}
