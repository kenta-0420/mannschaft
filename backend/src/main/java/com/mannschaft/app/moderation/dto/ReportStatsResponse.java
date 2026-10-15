package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通報統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReportStatsResponse {

    private final long pendingCount;
    private final long reviewingCount;
    private final long escalatedCount;
    private final long resolvedCount;
    private final long dismissedCount;
    private final long totalCount;
}
