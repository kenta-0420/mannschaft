package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WARNING再レビュー判定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReviewReReviewRequest {

    @NotBlank
    private final String status;

    @Size(max = 5000)
    private final String reviewNote;
}
