package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 応募承諾レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AcceptProposalResponse {

    private final Long proposalId;
    private final Long requestId;
    private final String status;
    private final Boolean scheduleCreated;
}
