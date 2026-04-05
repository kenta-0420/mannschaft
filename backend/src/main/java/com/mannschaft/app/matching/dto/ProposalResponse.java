package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 応募レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ProposalResponse {

    private final Long id;
    private final Long requestId;
    private final Long proposingTeamId;
    private final String message;
    private final String proposedVenue;
    private final String status;
    private final String statusReason;
    private final Long cancelledByTeamId;
    private final String cancellationType;
    private final LocalDateTime mutualAgreedAt;
    private final List<ProposedDateResponse> proposedDates;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
