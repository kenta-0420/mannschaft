package com.mannschaft.app.onboarding.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * オンボーディングテンプレート作成リクエスト。
 */
public record CreateOnboardingTemplateRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @Size(max = 5000) String welcomeMessage,
        Boolean isOrderEnforced,
        Integer deadlineDays,
        Integer reminderDaysBefore,
        Boolean isAdminNotifiedOnComplete,
        Boolean isTimelinePostedOnComplete,
        Long presetId,
        @Valid List<CreateStepRequest> steps
) {}
