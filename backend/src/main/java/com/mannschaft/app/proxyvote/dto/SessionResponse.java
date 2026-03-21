package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 投票セッション詳細レスポンスDTO。
 */
@Getter
@Builder
public class SessionResponse {

    private final Long id;
    private final String scopeType;
    private final Long teamId;
    private final Long organizationId;
    private final String resolutionMode;
    private final String title;
    private final String description;
    private final String status;
    private final LocalDate meetingDate;
    private final LocalDateTime votingStartAt;
    private final LocalDateTime votingEndAt;
    private final Boolean isAnonymous;
    private final Boolean isAutoAcceptDelegation;
    private final String quorumType;
    private final BigDecimal quorumThreshold;
    private final Integer eligibleCount;
    private final QuorumStatusResponse quorumStatus;
    private final List<MotionResponse> motions;
    private final List<AttachmentResponse> attachments;
    private final MyStatusResponse myStatus;
    private final Long version;
    private final Long createdBy;
    private final LocalDateTime createdAt;
}
