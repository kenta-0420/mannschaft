package com.mannschaft.app.school.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 評価解消リクエストDTO（F03.13 Phase 12）。 */
public record ResolveEvaluationRequest(
        @NotBlank @Size(max = 512) String resolutionNote
) {}
