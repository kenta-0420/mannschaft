package com.mannschaft.app.shift.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シフト希望提出サマリーレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShiftRequestSummaryResponse {

    private final Long scheduleId;
    private final long totalMembers;
    private final long submittedCount;
    private final long pendingCount;
}
