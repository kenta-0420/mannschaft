package com.mannschaft.app.residencestatus.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 年次更新回答 レスポンス DTO（F09.16）。
 */
@Data
@Builder
public class AnnualReviewResponseDto {

    private UUID id;
    private UUID annualReviewId;
    private Long organizationId;
    private Long dwellingUnitId;
    private Long residentRegistryId;
    private Long respondentUserId;
    private String residenceState;
    private Boolean contactPhoneVerified;
    private Boolean contactEmailVerified;
    private Boolean emergencyContactVerified;
    private String note;
    private LocalDateTime respondedAt;
    private LocalDateTime createdAt;
}
