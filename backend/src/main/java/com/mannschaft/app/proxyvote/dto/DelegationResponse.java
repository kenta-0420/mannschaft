package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 委任状レスポンスDTO。
 */
@Getter
@Builder
public class DelegationResponse {

    private final Long id;
    private final Long sessionId;
    private final Long delegatorId;
    private final Long delegateId;
    private final Boolean isBlank;
    private final Long electronicSealId;
    private final String reason;
    private final String status;
    private final Long reviewedBy;
    private final LocalDateTime reviewedAt;
    private final LocalDateTime createdAt;
}
