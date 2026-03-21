package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * リアクション作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateReactionRequest {

    @NotNull
    private final String targetType;

    @NotNull
    private final Long targetId;

    @NotBlank
    @Size(max = 10)
    private final String emoji;
}
