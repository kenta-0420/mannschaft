package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * スレッド作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateThreadRequest {

    @NotNull
    private final Long categoryId;

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotBlank
    private final String body;

    @Size(max = 20)
    private final String priority;

    @Size(max = 20)
    private final String readTrackingMode;

    private final String sourceType;

    private final Long sourceId;
}
