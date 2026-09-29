package com.mannschaft.app.recruitment.event;

/** 異議申立のコミット後に主催者へ知らせるための識別子。 */
public record RecruitmentNoShowDisputeNotificationEvent(Long recordId, Long listingId, Long actorUserId) {
}
