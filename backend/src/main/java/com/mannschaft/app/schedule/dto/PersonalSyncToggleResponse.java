package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 個人同期ON/OFFレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PersonalSyncToggleResponse {

    private final boolean personalSyncEnabled;
    private final int backfillCount;
}
