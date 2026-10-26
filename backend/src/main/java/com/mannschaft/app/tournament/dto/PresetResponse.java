package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * プリセットレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class PresetResponse {

    private Long id;
    private PresetContentDto content;
    private PresetScoringDto scoring;
    private Integer sortOrder;
    private List<TiebreakerResponse> tiebreakers;
    private List<StatDefResponse> statDefs;
    private PresetAuditDto audit;

    public record PresetContentDto(
            String name, String sportCategory, String description,
            String icon, String supportedFormats) {}

    public record PresetScoringDto(
            Integer winPoints, Integer drawPoints, Integer lossPoints,
            Boolean hasDraw, Boolean hasSets, Integer setsToWin,
            Boolean hasExtraTime, Boolean hasPenalties,
            String scoreUnitLabel, String bonusPointRules) {}

    public record PresetAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
