package com.mannschaft.app.tournament;

import com.mannschaft.app.tournament.dto.DivisionResponse;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.dto.MatchResponse;
import com.mannschaft.app.tournament.dto.MatchSetResponse;
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
import com.mannschaft.app.tournament.entity.TournamentMatchEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchPlayerStatEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchRosterEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchSetEntity;
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
        return new PresetResponse(
                entity.getId(), entity.getName(), entity.getSportCategory(),
                entity.getDescription(), entity.getIcon(), entity.getSupportedFormats(),
                entity.getWinPoints(), entity.getDrawPoints(), entity.getLossPoints(),
                entity.getHasDraw(), entity.getHasSets(), entity.getSetsToWin(),
                entity.getHasExtraTime(), entity.getHasPenalties(), entity.getScoreUnitLabel(),
                entity.getBonusPointRules(), entity.getSortOrder(),
                tiebreakers, statDefs,
                entity.getCreatedAt(), entity.getUpdatedAt());
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
        return new TemplateResponse(
                entity.getId(), entity.getOrganizationId(), entity.getSourcePresetId(),
                entity.getName(), entity.getDescription(), entity.getIcon(),
                entity.getSupportedFormats(), entity.getWinPoints(), entity.getDrawPoints(),
                entity.getLossPoints(), entity.getHasDraw(), entity.getHasSets(),
                entity.getSetsToWin(), entity.getHasExtraTime(), entity.getHasPenalties(),
                entity.getScoreUnitLabel(), entity.getBonusPointRules(), entity.getVersion(),
                entity.getCreatedBy(), tiebreakers, statDefs,
                entity.getCreatedAt(), entity.getUpdatedAt());
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
        return new TournamentResponse(
                entity.getId(), entity.getOrganizationId(), entity.getTemplateId(),
                entity.getPreviousTournamentId(), entity.getName(), entity.getDescription(),
                entity.getFormat().name(), entity.getSeason(), entity.getStartDate(),
                entity.getEndDate(), entity.getWinPoints(), entity.getDrawPoints(),
                entity.getLossPoints(), entity.getHasDraw(), entity.getHasSets(),
                entity.getSetsToWin(), entity.getHasExtraTime(), entity.getHasPenalties(),
                entity.getScoreUnitLabel(), entity.getBonusPointRules(),
                entity.getLeagueRoundType().name(), entity.getKnockoutLegs(),
                entity.getVisibility().name(), entity.getStatus().name(),
                entity.getVersion(), entity.getCreatedBy(),
                tiebreakers, statDefs,
                entity.getCreatedAt(), entity.getUpdatedAt());
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

    @Mapping(target = "tournamentId", source = "tournamentId")
    DivisionResponse toDivisionResponse(TournamentDivisionEntity entity);

    // ===== Participant =====

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    ParticipantResponse toParticipantResponse(TournamentParticipantEntity entity);

    // ===== Matchday =====

    default MatchdayResponse toMatchdayResponse(TournamentMatchdayEntity entity, List<MatchResponse> matches) {
        return new MatchdayResponse(entity.getId(), entity.getDivisionId(), entity.getName(),
                entity.getMatchdayNumber(), entity.getScheduledDate(), entity.getStatus().name(),
                matches, entity.getCreatedAt(), entity.getUpdatedAt());
    }

    // ===== Match =====

    default MatchResponse toMatchResponse(TournamentMatchEntity entity,
                                          List<MatchSetResponse> sets,
                                          List<PlayerStatResponse> playerStats) {
        return new MatchResponse(
                entity.getId(), entity.getMatchdayId(), entity.getHomeParticipantId(),
                entity.getAwayParticipantId(), entity.getMatchNumber(), entity.getScheduledDatetime(),
                entity.getVenue(), entity.getHomeScore(), entity.getAwayScore(),
                entity.getHomeExtraScore(), entity.getAwayExtraScore(),
                entity.getHomePenaltyScore(), entity.getAwayPenaltyScore(),
                entity.getWinnerParticipantId(), entity.getResult().name(),
                entity.getLeg(), entity.getNextMatchId(),
                entity.getNextMatchSlot() != null ? entity.getNextMatchSlot().name() : null,
                entity.getNotes(), entity.getScheduleId(), entity.getVersion(),
                entity.getStatus().name(), sets, playerStats,
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    default MatchSetResponse toMatchSetResponse(TournamentMatchSetEntity entity) {
        return new MatchSetResponse(entity.getId(), entity.getSetNumber(),
                entity.getHomeScore(), entity.getAwayScore());
    }

    default PlayerStatResponse toPlayerStatResponse(TournamentMatchPlayerStatEntity entity) {
        return new PlayerStatResponse(entity.getId(), entity.getMatchId(),
                entity.getParticipantId(), entity.getUserId(), entity.getStatKey(),
                entity.getValueInt(), entity.getValueDecimal(), entity.getValueTime());
    }

    // ===== Roster =====

    RosterResponse toRosterResponse(TournamentMatchRosterEntity entity);

    // ===== Standing =====

    default StandingResponse toStandingResponse(TournamentStandingEntity entity,
                                                Long teamId, String teamName) {
        return new StandingResponse(
                entity.getId(), entity.getDivisionId(), entity.getParticipantId(),
                teamId, teamName, entity.getRank(), entity.getPlayed(),
                entity.getWins(), entity.getDraws(), entity.getLosses(),
                entity.getScoreFor(), entity.getScoreAgainst(), entity.getScoreDifference(),
                entity.getPoints(), entity.getBonusPoints(), entity.getSetsWon(),
                entity.getSetsLost(), entity.getForm(),
                entity.getPromotionZone() != null ? entity.getPromotionZone().name() : null,
                entity.getLastCalculatedAt());
    }

    // ===== Individual Ranking =====

    default IndividualRankingResponse toIndividualRankingResponse(
            TournamentIndividualRankingEntity entity, String rankingLabel) {
        return new IndividualRankingResponse(
                entity.getId(), entity.getTournamentId(), entity.getUserId(),
                entity.getParticipantId(), entity.getStatKey(), rankingLabel,
                entity.getRank(), entity.getTotalValueInt(), entity.getTotalValueDecimal(),
                entity.getTotalValueTime(), entity.getMatchesPlayed(),
                entity.getLastCalculatedAt());
    }

    // ===== Promotion Record =====

    @Mapping(target = "type", expression = "java(entity.getType().name())")
    PromotionRecordResponse toPromotionRecordResponse(TournamentPromotionRecordEntity entity);
}
