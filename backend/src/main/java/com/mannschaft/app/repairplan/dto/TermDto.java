package com.mannschaft.app.repairplan.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 理事任期 DTO（F08.8 Phase 5）。
 */
public record TermDto(
        UUID id,
        Long scopeId,
        String scopeType,
        Long organizationId,
        Long userId,
        String userDisplayName,
        LocalDate termStart,
        LocalDate termEnd,
        String roleName,
        boolean isActive
) {
}
