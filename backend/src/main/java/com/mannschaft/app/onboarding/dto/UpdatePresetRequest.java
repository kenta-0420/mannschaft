package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingPresetCategory;
import jakarta.validation.constraints.Size;

/**
 * システムプリセット更新リクエスト。
 */
public record UpdatePresetRequest(
        @Size(max = 200) String name,
        @Size(max = 2000) String description,
        OnboardingPresetCategory category,
        @Size(max = 5000) String welcomeMessage,
        Boolean isOrderEnforced,
        Integer deadlineDays,
        String stepsJson,
        Boolean isActive,
        Integer sortOrder
) {}
