package com.mannschaft.app.promotion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セグメントプリセット作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSegmentPresetRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @NotBlank
    private final String conditions;
}
