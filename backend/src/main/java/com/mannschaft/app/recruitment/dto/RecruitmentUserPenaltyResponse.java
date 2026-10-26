package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F03.11 Phase 5b: ユーザーペナルティレスポンス DTO。
 */
@Getter
@AllArgsConstructor
public class RecruitmentUserPenaltyResponse {

    private final Long id;
    private final Long userId;
    private final String scopeType;
    private final Long scopeId;
    private final String penaltyType;
    private final String startedAt;
    private final String expiresAt;
    private final String liftedAt;
    private final String liftReason;
    private final boolean isActive;
    private final String createdAt;
}
