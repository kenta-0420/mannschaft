package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 定足数充足状況レスポンスDTO。
 */
@Getter
@Builder
public class QuorumStatusResponse {

    private final Integer required;
    private final Integer current;
    private final Boolean isMet;
    private final Long votedCount;
    private final Long delegatedCount;
    private final Long notRespondedCount;
}
