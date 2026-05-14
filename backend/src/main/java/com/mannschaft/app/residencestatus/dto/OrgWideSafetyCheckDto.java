package com.mannschaft.app.residencestatus.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.16 S3-C 管理組合横展開安否確認ラッパ DTO。
 */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OrgWideSafetyCheckDto {

    private UUID id;
    private Long organizationId;

    /** F03.6 safety_checks.id（v1 では null） */
    private Long safetyCheckId;

    /** 発動者（理事長）user_id */
    private Long triggeredBy;

    private LocalDateTime triggeredAt;
    private String triggerReason;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
}
