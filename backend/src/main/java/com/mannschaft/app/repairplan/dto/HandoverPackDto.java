package com.mannschaft.app.repairplan.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 申し送りパック DTO（F08.8 Phase 5）。
 */
public record HandoverPackDto(
        UUID id,
        Long scopeId,
        String scopeType,
        Long organizationId,
        /** GENERATING / READY / FAILED */
        String status,
        /** STANDARD / ANONYMIZED */
        String piiLevel,
        String fileSha256,
        Long fileSizeBytes,
        Integer termYear,
        LocalDate periodStart,
        LocalDate periodEnd,
        String memo,
        LocalDateTime generatedAt,
        LocalDateTime expiresAt
) {
}
