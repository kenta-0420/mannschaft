package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * フィーチャーフラグレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FeatureFlagResponse {

    private final Long id;
    private final String flagKey;
    private final Boolean isEnabled;
    private final String description;
    private final Long updatedBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
