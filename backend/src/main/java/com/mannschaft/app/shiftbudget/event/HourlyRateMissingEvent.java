package com.mannschaft.app.shiftbudget.event;

import java.util.List;

/** 時給未設定を業務コミット後に通知するためのイベント。 */
public record HourlyRateMissingEvent(Long organizationId, Long teamId, String teamSlug,
                                     Long scheduleId, List<Long> missingUserIds) {
}
