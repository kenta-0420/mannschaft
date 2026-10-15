package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 結果確定レスポンスDTO。
 */
@Getter
@Builder
public class FinalizeResponse {

    private final Long sessionId;
    private final String status;
    private final Boolean quorumMet;
    private final QuorumStatusResponse quorumStatus;
    private final List<MotionFinalizeResponse> motions;
    private final String message;

    /**
     * 議案別確定結果。
     */
    @Getter
    @Builder
    public static class MotionFinalizeResponse {
        private final Long id;
        private final String result;
        private final Boolean isAdvisory;
    }
}
