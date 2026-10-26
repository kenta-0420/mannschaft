package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 今日の当番レスポンスDTO（ダッシュボードウィジェット用）。
 */
@Getter
@RequiredArgsConstructor
public class DutyTodayResponse {

    private final Long dutyId;
    private final String dutyName;
    private final String icon;
    private final Long assigneeUserId;
    private final String rotationType;
}
