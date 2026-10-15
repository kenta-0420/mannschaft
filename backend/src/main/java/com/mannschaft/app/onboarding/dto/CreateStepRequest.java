package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingStepType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * オンボーディングテンプレートステップ作成リクエスト。
 */
public record CreateStepRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description,
        @NotNull OnboardingStepType stepType,
        Long referenceId,
        @Size(max = 2048) String referenceUrl,
        Integer deadlineOffsetDays,
        @NotNull Integer sortOrder
) {}
