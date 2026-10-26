package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 応募作成レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ProposalCreateResponse {

    private final Long id;
    private final Long requestId;
    private final String status;
}
