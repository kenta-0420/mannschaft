package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 投票結果レスポンスDTO。
 */
@Getter
@Builder
public class VoteResultsResponse {

    private final Long sessionId;
    private final String status;
    private final QuorumStatusResponse quorumStatus;
    private final List<MotionResultResponse> motions;
    private final SummaryResponse summary;

    /**
     * 議案別結果。
     */
    @Getter
    @Builder
    public static class MotionResultResponse {
        private final Long id;
        private final Integer motionNumber;
        private final String title;
        private final String requiredApproval;
        private final String result;
        private final Integer approveCount;
        private final Integer rejectCount;
        private final Integer abstainCount;
        private final BigDecimal approveRate;
        private final Integer totalVotes;
    }

    /**
     * 全体サマリー。
     */
    @Getter
    @Builder
    public static class SummaryResponse {
        private final Integer totalEligible;
        private final Long totalVoted;
        private final Long totalDelegated;
        private final Long totalNotResponded;
        private final BigDecimal participationRate;
    }
}
