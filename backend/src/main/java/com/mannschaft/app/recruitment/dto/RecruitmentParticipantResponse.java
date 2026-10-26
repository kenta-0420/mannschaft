package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * F03.11 参加者の単体レスポンス。
 */
@Getter
@AllArgsConstructor
public class RecruitmentParticipantResponse {

    private final Long id;
    private final Long listingId;
    private final String participantType;
    private final Long userId;
    private final Long teamId;
    private final Long appliedBy;
    private final String status;
    private final Integer waitlistPosition;
    private final String note;
    private final LocalDateTime appliedAt;
    private final LocalDateTime statusChangedAt;
}
