package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F03.11 Phase 5b: NO_SHOW 記録レスポンス DTO。
 */
@Getter
@AllArgsConstructor
public class RecruitmentNoShowRecordResponse {

    private final Long id;
    private final Long participantId;
    private final Long listingId;
    private final Long userId;
    private final String reason;
    private final boolean confirmed;
    private final String recordedAt;
    private final Long recordedBy;
    private final boolean disputed;
    private final String disputeResolution;
    private final String createdAt;
}
