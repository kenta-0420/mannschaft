package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 手動再同期レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ManualSyncResponse {

    private final int backfillCount;
    private final String message;
}
