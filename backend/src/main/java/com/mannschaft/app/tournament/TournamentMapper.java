package com.mannschaft.app.tournament;

import com.mannschaft.app.publicview.visibility.DisplayIdentity;
import com.mannschaft.app.tournament.dto.DivisionResponse;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.dto.FixtureResponse;
import com.mannschaft.app.tournament.dto.FixtureSetResponse;
import com.mannschaft.app.tournament.dto.MatchdayResponse;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.dto.PlayerStatResponse;
import com.mannschaft.app.tournament.dto.PresetResponse;
import com.mannschaft.app.tournament.dto.PromotionRecordResponse;
import com.mannschaft.app.tournament.dto.RosterResponse;
import com.mannschaft.app.tournament.dto.StatDefResponse;
import com.mannschaft.app.tournament.dto.StandingResponse;
import com.mannschaft.app.tournament.dto.TemplateResponse;
import com.mannschaft.app.tournament.dto.TiebreakerResponse;
import com.mannschaft.app.tournament.dto.TournamentResponse;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetEntity;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetStatDefEntity;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetTiebreakerEntity;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentIndividualRankingEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import com.mannschaft.app.tournament.entity.TournamentFixturePlayerStatEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureRosterEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureSetEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentPromotionRecordEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.entity.TournamentStatDefEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateTiebreakerEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateStatDefEntity;
import com.mannschaft.app.tournament.entity.TournamentTiebreakerEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Collections;
import java.util.List;

/**
 * 大会・リーグ管理機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface TournamentMapper {

    // ===== Preset =====

    default PresetResponse toPresetResponse(SystemTournamentPresetEntity entity,
                                            List<TiebreakerResponse> tiebreakers,
                                            List<StatDefResponse> statDefs) {
        return PresetResponse.builder()
                .id(entity.getId())
                .content(new PresetResponse.PresetContentDto(
                        entity.getName(), entity.getSportCategory(),
                        entity.getDescription(), entity.getIcon(), entity.getSupportedFormats()))
                .scoring(new PresetResponse.PresetScoringDto(
                        entity.getWinPoints(), entity.getDrawPoints(), entity.getLossPoints(),
                        entity.getHasDraw(), entity.getHasSets(), entity.getSetsToWin(),
                        entity.getHasExtraTime(), entity.getHasPenalties(),
                        entity.getScoreUnitLabel(), entity.getBonusPointRules()))
                .sortOrder(entity.getSortOrder())
                .tiebreakers(tiebreakers)
                .statDefs(statDefs)
                .audit(new PresetResponse.PresetAuditDto(entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    default PresetResponse toPresetSummaryResponse(SystemTournamentPresetEntity entity) {
        return toPresetResponse(entity, Collections.emptyList(), Collections.emptyList());
    }

    default TiebreakerResponse toTiebreakerResponse(SystemTournamentPresetTiebreakerEntity entity) {
        return new TiebreakerResponse(entity.getId(), entity.getPriority(),
                entity.getCriteria().name(), entity.getDirection().name());
    }

    default StatDefResponse toStatDefResponse(SystemTournamentPresetStatDefEntity entity) {
        return new StatDefResponse(entity.getId(), entity.getName(), entity.getStatKey(),
                entity.getUnit(), entity.getDataType().name(), entity.getAggregationType().name(),
                entity.getIsRankingTarget(), entity.getRankingLabel(), entity.getSortOrder());
    }

    // ===== Template =====

    default TemplateResponse toTemplateResponse(TournamentTemplateEntity entity,
                                                List<TiebreakerResponse> tiebreakers,
                                                List<StatDefResponse> statDefs) {
        return TemplateResponse.builder()
                .id(entity.getId())
                .scope(new TemplateResponse.TemplateScopeDto(
                        entity.getOrganizationId(), entity.getSourcePresetId(), entity.getCreatedBy()))
                .content(new TemplateResponse.TemplateContentDto(
                        entity.getName(), entity.getDescription(),
                        entity.getIcon(), entity.getSupportedFormats()))
                .scoring(new TemplateResponse.TemplateScoringDto(
                        entity.getWinPoints(), entity.getDrawPoints(), entity.getLossPoints(),
                        entity.getHasDraw(), entity.getHasSets(), entity.getSetsToWin(),
                        entity.getHasExtraTime(), entity.getHasPenalties(),
                        entity.getScoreUnitLabel(), entity.getBonusPointRules()))
                .tiebreakers(tiebreakers)
                .statDefs(statDefs)
                .audit(new TemplateResponse.TemplateAuditDto(
                        entity.getVersion(), entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    default TiebreakerResponse toTiebreakerResponse(TournamentTemplateTiebreakerEntity entity) {
        return new TiebreakerResponse(entity.getId(), entity.getPriority(),
                entity.getCriteria().name(), entity.getDirection().name());
    }

    default StatDefResponse toStatDefResponse(TournamentTemplateStatDefEntity entity) {
        return new StatDefResponse(entity.getId(), entity.getName(), entity.getStatKey(),
                entity.getUnit(), entity.getDataType().name(), entity.getAggregationType().name(),
                entity.getIsRankingTarget(), entity.getRankingLabel(), entity.getSortOrder());
    }

    // ===== Tournament =====

    default TournamentResponse toTournamentResponse(TournamentEntity entity,
                                                    List<TiebreakerResponse> tiebreakers,
                                                    List<StatDefResponse> statDefs) {
        return TournamentResponse.builder()
                .id(entity.getId())
                .scope(new TournamentResponse.TournamentScopeDto(
                        entity.getOrganizationId(), entity.getTemplateId(),
                        entity.getPreviousTournamentId()))
                .content(new TournamentResponse.TournamentContentDto(
                        entity.getName(), entity.getDescription(), entity.getFormat().name(),
                        entity.getSeason(), entity.getStartDate(), entity.getEndDate()))
                .scoring(new TournamentResponse.TournamentScoringDto(
                        entity.getWinPoints(), entity.getDrawPoints(), entity.getLossPoints(),
                        entity.getHasDraw(), entity.getHasSets(), entity.getSetsToWin(),
                        entity.getHasExtraTime(), entity.getHasPenalties(),
                        entity.getScoreUnitLabel(), entity.getBonusPointRules()))
                .structure(new TournamentResponse.TournamentStructureDto(
                        entity.getLeagueRoundType().name(), entity.getKnockoutLegs(),
                        entity.getVisibility().name(), entity.getStatus().name()))
                .tiebreakers(tiebreakers)
                .statDefs(statDefs)
                .audit(new TournamentResponse.TournamentAuditDto(
                        entity.getVersion(), entity.getCreatedBy(),
                        entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    default TournamentResponse toTournamentSummaryResponse(TournamentEntity entity) {
        return toTournamentResponse(entity, Collections.emptyList(), Collections.emptyList());
    }

    default TiebreakerResponse toTiebreakerResponse(TournamentTiebreakerEntity entity) {
        return new TiebreakerResponse(entity.getId(), entity.getPriority(),
                entity.getCriteria().name(), entity.getDirection().name());
    }

    default StatDefResponse toStatDefResponse(TournamentStatDefEntity entity) {
        return new StatDefResponse(entity.getId(), entity.getName(), entity.getStatKey(),
                entity.getUnit(), entity.getDataType().name(), entity.getAggregationType().name(),
                entity.getIsRankingTarget(), entity.getRankingLabel(), entity.getSortOrder());
    }

    // ===== Division =====

    default DivisionResponse toDivisionResponse(TournamentDivisionEntity entity) {
        return DivisionResponse.builder()
                .id(entity.getId())
                .tournamentId(entity.getTournamentId())
                .name(entity.getName())
                .level(entity.getLevel())
                .slots(new DivisionResponse.DivisionSlotsDto(
                        entity.getPromotionSlots(), entity.getRelegationSlots(),
                        entity.getPlayoffPromotionSlots(), entity.getMaxParticipants(),
                        entity.getMinEntryCount(), entity.getMaxEntryCount(),
                        entity.getSortOrder()))
                .audit(new DivisionResponse.DivisionAuditDto(
                        entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    // ===== Participant =====

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    ParticipantResponse toParticipantResponse(TournamentParticipantEntity entity);

    // ===== Matchday =====

    default MatchdayResponse toMatchdayResponse(TournamentMatchdayEntity entity, List<FixtureResponse> matches) {
        return new MatchdayResponse(entity.getId(), entity.getDivisionId(), entity.getName(),
                entity.getMatchdayNumber(), entity.getScheduledDate(), entity.getStatus().name(),
                matches, entity.getCreatedAt(), entity.getUpdatedAt());
    }

    // ===== Match =====

    default FixtureResponse toMatchResponse(TournamentFixtureEntity entity,
                                          List<FixtureSetResponse> sets,
                                          List<PlayerStatResponse> playerStats) {
        return FixtureResponse.builder()
                .id(entity.getId())
                .matchdayId(entity.getMatchdayId())
                .participants(new FixtureResponse.MatchParticipantsDto(
                        entity.getHomeParticipantId(), entity.getAwayParticipantId(),
                        entity.getWinnerParticipantId()))
                .score(new FixtureResponse.MatchScoreDto(
                        entity.getHomeScore(), entity.getAwayScore(),
                        entity.getHomeExtraScore(), entity.getAwayExtraScore(),
                        entity.getHomePenaltyScore(), entity.getAwayPenaltyScore()))
                .info(new FixtureResponse.MatchInfoDto(
                        entity.getMatchNumber(), entity.getScheduledDatetime(), entity.getVenue(),
                        entity.getResult().name(), entity.getLeg(), entity.getNotes(),
                        entity.getStatus().name(), entity.getNextMatchId(),
                        entity.getNextMatchSlot() != null ? entity.getNextMatchSlot().name() : null,
                        entity.getScheduleId()))
                .sets(sets)
                .playerStats(playerStats)
                .audit(new FixtureResponse.MatchAuditDto(
                        entity.getVersion(), entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    default FixtureSetResponse toMatchSetResponse(TournamentFixtureSetEntity entity) {
        return new FixtureSetResponse(entity.getId(), entity.getSetNumber(),
                entity.getHomeScore(), entity.getAwayScore());
    }

    default PlayerStatResponse toPlayerStatResponse(TournamentFixturePlayerStatEntity entity) {
        return new PlayerStatResponse(entity.getId(), entity.getMatchId(),
                entity.getParticipantId(), entity.getUserId(), entity.getStatKey(),
                entity.getValueInt(), entity.getValueDecimal(), entity.getValueTime());
    }

    // ===== Roster =====

    RosterResponse toRosterResponse(TournamentFixtureRosterEntity entity);

    // ===== Standing =====

    default StandingResponse toStandingResponse(TournamentStandingEntity entity,
                                                Long teamId, String teamName) {
        return StandingResponse.builder()
                .id(entity.getId())
                .meta(new StandingResponse.StandingMetaDto(
                        entity.getDivisionId(), entity.getParticipantId()))
                .team(new StandingResponse.StandingTeamDto(teamId, teamName, entity.getRank()))
                .record(new StandingResponse.StandingRecordDto(
                        entity.getPlayed(), entity.getWins(),
                        entity.getDraws(), entity.getLosses()))
                .score(new StandingResponse.StandingScoreDto(
                        entity.getScoreFor(), entity.getScoreAgainst(), entity.getScoreDifference(),
                        entity.getPoints(), entity.getBonusPoints(),
                        entity.getSetsWon(), entity.getSetsLost()))
                .form(entity.getForm())
                .status(new StandingResponse.StandingStatusDto(
                        entity.getPromotionZone() != null ? entity.getPromotionZone().name() : null,
                        entity.getLastCalculatedAt()))
                .build();
    }

    // ===== Individual Ranking =====

    /**
     * 個人ランキング Entity を DTO に変換する。
     *
     * <p>F08.7 順位UI 項目①: 選手の表示名は F19.1 本人可視性経由で解決済みの {@link DisplayIdentity}
     * を受け取り、{@code displayName} / {@code anonymized} / {@code avatarUrl} を context に詰める。
     * MINOR 保護・退会済み・本名/サポーター開示規約の判定は呼び出し側
     * （{@code RankingsCalculationService} → {@code IdentityVisibilityResolver}）で済んでいる前提。</p>
     *
     * @param entity       ランキング Entity
     * @param rankingLabel 成績項目のランキングラベル（{@code null} 可）
     * @param identity     F19.1 経由で解決済みの表示識別（{@code null} 不可）
     */
    default IndividualRankingResponse toIndividualRankingResponse(
            TournamentIndividualRankingEntity entity, String rankingLabel, DisplayIdentity identity) {
        return IndividualRankingResponse.builder()
                .id(entity.getId())
                .context(new IndividualRankingResponse.IndividualRankingContextDto(
                        entity.getTournamentId(), entity.getUserId(),
                        entity.getParticipantId(), entity.getMatchesPlayed(),
                        identity.displayLabel(), identity.anonymized(), identity.avatarUrl()))
                .stat(new IndividualRankingResponse.IndividualRankingStatDto(
                        entity.getStatKey(), rankingLabel,
                        entity.getTotalValueInt(), entity.getTotalValueDecimal(),
                        entity.getTotalValueTime()))
                .rank(entity.getRank())
                .lastCalculatedAt(entity.getLastCalculatedAt())
                .build();
    }

    // ===== Promotion Record =====

    default PromotionRecordResponse toPromotionRecordResponse(TournamentPromotionRecordEntity entity) {
        return PromotionRecordResponse.builder()
                .id(entity.getId())
                .context(new PromotionRecordResponse.PromotionRecordContextDto(
                        entity.getTournamentId(), entity.getTeamId()))
                .detail(new PromotionRecordResponse.PromotionRecordDetailDto(
                        entity.getFromDivisionId(), entity.getToDivisionId(),
                        entity.getType().name(), entity.getFinalRank(), entity.getReason()))
                .execution(new PromotionRecordResponse.PromotionRecordExecutionDto(
                        entity.getExecutedBy(), entity.getExecutedAt()))
                .build();
    }
}
