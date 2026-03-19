package com.mannschaft.app.dashboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 一括割り当て結果レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class BulkAssignResultResponse {

    private final int assignedCount;
    private final int skippedCount;
}
