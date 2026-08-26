package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 大会レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class TournamentResponse {

    private Long id;
    private TournamentScopeDto scope;
    private TournamentContentDto content;
    private TournamentScoringDto scoring;
    private TournamentStructureDto structure;
    private List<TiebreakerResponse> tiebreakers;
    private List<StatDefResponse> statDefs;
    private TournamentAuditDto audit;

    public record TournamentScopeDto(Long organizationId, Long templateId, Long previousTournamentId) {}

    public record TournamentContentDto(
            String name, String description, String format, String sport, String season,
            LocalDate startDate, LocalDate endDate) {}

    public record TournamentScoringDto(
            Integer winPoints, Integer drawPoints, Integer lossPoints,
            Boolean hasDraw, Boolean hasSets, Integer setsToWin,
            Boolean hasExtraTime, Boolean hasPenalties,
            String scoreUnitLabel, String bonusPointRules) {}

    public record TournamentStructureDto(
            String leagueRoundType, Integer knockoutLegs,
            String visibility, String status) {}

    public record TournamentAuditDto(
            Long version, Long createdBy,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
