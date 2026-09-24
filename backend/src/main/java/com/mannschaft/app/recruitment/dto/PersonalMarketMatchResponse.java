package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import java.time.Instant;

/** 個人市の札主向けマッチング表示。応募者を識別できる項目は返さない。 */
public record PersonalMarketMatchResponse(
        Long participantId,
        RecruitmentParticipantType participantType,
        RecruitmentParticipantStatus status,
        Integer waitlistPosition,
        Instant appliedAt,
        Instant statusChangedAt) {
}
