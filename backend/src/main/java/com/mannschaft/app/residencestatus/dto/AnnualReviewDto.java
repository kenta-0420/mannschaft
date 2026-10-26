package com.mannschaft.app.residencestatus.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 年次更新キャンペーン レスポンス DTO（F09.16）。
 */
@Data
@Builder
public class AnnualReviewDto {

    private UUID id;
    private Long organizationId;
    private Integer reviewYear;
    private LocalDateTime startedAt;
    private LocalDateTime deadlineAt;
    /** null = 進行中 */
    private LocalDateTime closedAt;
    private Integer targetCount;
    private Integer responseCount;
    private Long createdBy;
    private LocalDateTime createdAt;
}
