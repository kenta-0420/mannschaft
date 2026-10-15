package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約: 配信対象レスポンス。
 */
@Getter
@AllArgsConstructor
public class RecruitmentDistributionTargetResponse {

    private final Long id;
    private final Long listingId;
    private final String targetType;
    private final LocalDateTime createdAt;
}
