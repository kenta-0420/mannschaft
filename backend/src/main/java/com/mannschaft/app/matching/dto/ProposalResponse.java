package com.mannschaft.app.matching.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 応募レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ProposalResponse {

    private final Long id;
    private final Long requestId;
    private final Long proposingTeamId;
    private final ProposalContentDto content;
    private final ProposalStatusDto status;
    private final List<ProposedDateResponse> proposedDates;
    private final ProposalAuditDto audit;

    public record ProposalContentDto(
            String message,
            String proposedVenue) {}

    public record ProposalStatusDto(
            String status,
            String statusReason,
            Long cancelledByTeamId,
            String cancellationType,
            LocalDateTime mutualAgreedAt) {}

    public record ProposalAuditDto(
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}
}
