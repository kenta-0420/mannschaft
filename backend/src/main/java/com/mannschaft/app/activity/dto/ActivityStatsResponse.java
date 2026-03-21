package com.mannschaft.app.activity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 活動統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ActivityStatsResponse {

    private final long totalActivities;
    private final long totalParticipants;
}
