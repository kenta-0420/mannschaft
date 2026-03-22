package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 異議申立てレビューリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReviewAppealRequest {

    @NotBlank
    private final String status;

    @Size(max = 5000)
    private final String reviewNote;
}
