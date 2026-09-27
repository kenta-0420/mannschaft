package com.mannschaft.app.recruitment.event;

/** 新規 NO_SHOW 記録のコミット後に本人へ配送するための識別子。 */
public record RecruitmentNoShowNotificationEvent(Long recordId, Long listingId, Long recipientUserId) {
}
