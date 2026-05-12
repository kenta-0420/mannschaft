package com.mannschaft.app.repairplan.dto;

import com.mannschaft.app.repairplan.engine.GenerationMeter;
import com.mannschaft.app.repairplan.engine.YearlyBalance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 修繕シミュレーションシナリオ応答 DTO（F08.8 Phase 2）。
 */
public record ScenarioDto(
        UUID id,
        String name,
        String description,
        String engineVersion,
        String contentSha256,
        List<YearlyBalance> yearlyBalances,
        Integer depletionYear,
        Map<String, GenerationMeter> generationMeters,
        List<String> warnings,
        LocalDateTime baselineAt,
        LocalDateTime lockedAt,
        Long publishedAnnouncementId,
        Long pinnedCorkboardId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
