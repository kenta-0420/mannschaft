package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 議案レスポンスDTO。
 */
@Getter
@Builder
public class MotionResponse {

    private final Long id;
    private final Integer motionNumber;
    private final String title;
    private final String description;
    private final String votingStatus;
    private final LocalDateTime voteDeadlineAt;
    private final String requiredApproval;
    private final String result;
    private final Integer approveCount;
    private final Integer rejectCount;
    private final Integer abstainCount;
}
