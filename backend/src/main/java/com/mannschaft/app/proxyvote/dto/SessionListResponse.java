package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 投票セッション一覧レスポンスDTO。
 */
@Getter
@Builder
public class SessionListResponse {

    private final Long id;
    private final String scopeType;
    private final String resolutionMode;
    private final String title;
    private final String status;
    private final LocalDate meetingDate;
    private final LocalDateTime votingStartAt;
    private final LocalDateTime votingEndAt;
    private final Boolean isAnonymous;
    private final Integer eligibleCount;
    private final Integer motionCount;
    private final QuorumStatusResponse quorumStatus;
    private final MyStatusResponse myStatus;
    private final LocalDateTime createdAt;
}
