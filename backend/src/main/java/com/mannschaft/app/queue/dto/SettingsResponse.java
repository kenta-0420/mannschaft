package com.mannschaft.app.queue.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 順番待ち設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SettingsResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Short noShowTimeoutMinutes;
    private final Boolean noShowPenaltyEnabled;
    private final Short noShowPenaltyThreshold;
    private final Short noShowPenaltyDays;
    private final Short maxActiveTicketsPerUser;
    private final Boolean allowGuestQueue;
    private final Short almostReadyThreshold;
    private final Short holdExtensionMinutes;
    private final Boolean autoAdjustServiceMinutes;
    private final Boolean displayBoardPublic;
}
