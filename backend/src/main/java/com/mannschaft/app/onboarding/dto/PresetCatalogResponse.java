package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingPresetCategory;

/**
 * システムプリセットカタログレスポンス（ADMIN用）。
 */
public record PresetCatalogResponse(
        Long id,
        String name,
        String description,
        OnboardingPresetCategory category,
        String welcomeMessage,
        Boolean isOrderEnforced,
        Integer deadlineDays,
        String stepsJson
) {}
