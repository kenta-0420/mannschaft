package com.mannschaft.app.queue.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 順番待ち設定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class QueueSettingsRequest {

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
