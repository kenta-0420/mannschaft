package com.mannschaft.app.residencestatus.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.16 S3-C 見守り委員訪問記録 DTO。
 */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MonitoringCommitteeVisitDto {

    private UUID id;
    private Long organizationId;
    private Long committeeId;
    private Long residentRegistryId;
    private Long dwellingUnitId;
    private Long subjectUserId;
    private Long visitorUserId;
    private LocalDateTime visitedAt;

    /** 訪問結果 enum の name() 文字列 */
    private String contactResult;

    /** 配慮事項メモ（復号後の平文） */
    private String considerationMemo;

    private LocalDate nextVisitRecommendedAt;
    private UUID consentCovenantId;
    private LocalDateTime createdAt;
}
