package com.mannschaft.app.promotion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セグメント条件DTO。
 */
@Getter
@RequiredArgsConstructor
public class SegmentCondition {

    @NotBlank
    @Size(max = 30)
    private final String segmentType;

    @NotBlank
    private final String segmentValue;
}
