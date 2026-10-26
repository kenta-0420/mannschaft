package com.mannschaft.app.residencestatus.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 居住者アクティビティ日次スナップショット DTO（F09.16 S3-B）。
 *
 * <p>activityScoreTotal は本人非開示・ADMIN/WATCHER のみ参照可。
 */
@Data
@Builder
public class ActivitySnapshotDto {

    private UUID id;
    private Long organizationId;
    private Long residentRegistryId;
    private Long subjectUserId;
    private LocalDate snapshotDate;

    /** 合計重みスコア（0-100）。本人非開示・ADMIN/WATCHER のみ参照可 */
    private Integer activityScoreTotal;

    /** 各 activity_kind の発生回数 JSON 文字列 */
    private String activityBreakdownJson;

    private LocalDateTime createdAt;
}
