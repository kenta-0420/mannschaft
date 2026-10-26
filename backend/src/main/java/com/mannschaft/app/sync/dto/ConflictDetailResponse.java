package com.mannschaft.app.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * コンフリクト詳細レスポンス。clientData / serverData の JSON を含む。
 */
@Getter
@AllArgsConstructor
public class ConflictDetailResponse {

    private final Long id;
    private final Long userId;
    private final String resourceType;
    private final Long resourceId;
    private final String clientData;
    private final String serverData;
    private final Long clientVersion;
    private final Long serverVersion;
    private final String resolution;
    private final LocalDateTime resolvedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
