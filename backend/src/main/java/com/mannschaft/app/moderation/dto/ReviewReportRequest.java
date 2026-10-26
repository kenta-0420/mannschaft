package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コンテンツ通報レビューリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReviewReportRequest {

    @NotBlank
    private final String status;

    @Size(max = 1000)
    private final String reviewNote;
}
