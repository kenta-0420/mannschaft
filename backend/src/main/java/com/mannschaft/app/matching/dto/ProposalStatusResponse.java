package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 応募ステータス変更レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ProposalStatusResponse {

    private final Long proposalId;
    private final String status;
}
