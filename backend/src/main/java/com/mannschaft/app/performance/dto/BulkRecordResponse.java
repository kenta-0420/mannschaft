package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 一括記録入力レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkRecordResponse {

    private final int createdCount;
    private final Long scheduleId;
    private final String scheduleName;
    private final LocalDate recordedDate;
}
