package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コンテンツ通報作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateReportRequest {

    @NotBlank
    private final String targetType;

    @NotNull
    private final Long targetId;

    @NotBlank
    private final String reason;

    @Size(max = 1000)
    private final String description;

    private final String scopeType;

    private final Long scopeId;
}
