package com.mannschaft.app.promotion.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * セグメントプリセットレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SegmentPresetResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String name;
    private final String conditions;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
