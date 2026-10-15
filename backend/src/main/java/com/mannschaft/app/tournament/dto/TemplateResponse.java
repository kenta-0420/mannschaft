package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * テンプレートレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class TemplateResponse {

    private Long id;
    private TemplateScopeDto scope;
    private TemplateContentDto content;
    private TemplateScoringDto scoring;
    private List<TiebreakerResponse> tiebreakers;
    private List<StatDefResponse> statDefs;
    private TemplateAuditDto audit;

    public record TemplateScopeDto(Long organizationId, Long sourcePresetId, Long createdBy) {}

    public record TemplateContentDto(
            String name, String description, String icon, String supportedFormats) {}

    public record TemplateScoringDto(
            Integer winPoints, Integer drawPoints, Integer lossPoints,
            Boolean hasDraw, Boolean hasSets, Integer setsToWin,
            Boolean hasExtraTime, Boolean hasPenalties,
            String scoreUnitLabel, String bonusPointRules) {}

    public record TemplateAuditDto(Long version, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
