package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WARNING再レビュー昇格リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class EscalateReReviewRequest {

    @NotBlank
    @Size(max = 5000)
    private final String escalationReason;
}
