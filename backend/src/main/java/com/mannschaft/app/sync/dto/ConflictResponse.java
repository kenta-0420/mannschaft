package com.mannschaft.app.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * コンフリクト一覧用の要約レスポンス。
 */
@Getter
@AllArgsConstructor
public class ConflictResponse {

    private final Long id;
    private final String resourceType;
    private final Long resourceId;
    private final Long clientVersion;
    private final Long serverVersion;
    private final String resolution;
    private final LocalDateTime resolvedAt;
    private final LocalDateTime createdAt;
}
