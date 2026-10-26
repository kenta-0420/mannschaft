package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 議案投票終了レスポンスDTO。
 */
@Getter
@Builder
public class EndVoteResponse {

    private final Long motionId;
    private final String votingStatus;
    private final String result;
    private final Integer approveCount;
    private final Integer rejectCount;
    private final Integer abstainCount;
    private final BigDecimal approveRate;
    private final Integer totalVotes;
}
