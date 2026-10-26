package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ヤバいやつ解除申請レビューリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReviewUnflagRequest {

    @NotBlank
    private final String status;

    @Size(max = 5000)
    private final String reviewNote;
}
