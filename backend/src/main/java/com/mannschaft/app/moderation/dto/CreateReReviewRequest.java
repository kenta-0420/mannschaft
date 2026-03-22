package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WARNING再レビュー作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateReReviewRequest {

    @NotNull
    private final Long reportId;

    @NotBlank
    @Size(max = 5000)
    private final String reason;
}
