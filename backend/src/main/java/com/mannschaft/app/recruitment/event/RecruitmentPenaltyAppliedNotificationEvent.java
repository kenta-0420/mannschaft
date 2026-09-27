package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import java.time.LocalDateTime;

/** 新規ペナルティの確定後に本人へ適用通知を配送するためのイベント。 */
public record RecruitmentPenaltyAppliedNotificationEvent(
        Long penaltyId,
        Long recipientUserId,
        RecruitmentScopeType sourceScopeType,
        Long sourceScopeId,
        LocalDateTime expiresAt) {
}
