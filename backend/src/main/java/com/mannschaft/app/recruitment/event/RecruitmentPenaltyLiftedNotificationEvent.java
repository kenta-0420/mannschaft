package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.recruitment.RecruitmentScopeType;

/** 自動期限解除がコミットした後に本人通知を配送するイベント。 */
public record RecruitmentPenaltyLiftedNotificationEvent(
        Long penaltyId,
        Long recipientUserId,
        RecruitmentScopeType scopeType,
        Long scopeId) {
}
