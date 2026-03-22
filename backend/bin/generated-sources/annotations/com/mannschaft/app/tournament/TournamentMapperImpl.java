package com.mannschaft.app.tournament;

import com.mannschaft.app.tournament.dto.DivisionResponse;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.dto.PromotionRecordResponse;
import com.mannschaft.app.tournament.dto.RosterResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchRosterEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentPromotionRecordEntity;
import java.time.LocalDateTime;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:34+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class TournamentMapperImpl implements TournamentMapper {

    @Override
    public DivisionResponse toDivisionResponse(TournamentDivisionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long tournamentId = null;
        Long id = null;
        String name = null;
        Integer level = null;
        Integer promotionSlots = null;
        Integer relegationSlots = null;
        Integer playoffPromotionSlots = null;
        Integer maxParticipants = null;
        Integer sortOrder = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        tournamentId = entity.getTournamentId();
        id = entity.getId();
        name = entity.getName();
        level = entity.getLevel();
        promotionSlots = entity.getPromotionSlots();
        relegationSlots = entity.getRelegationSlots();
        playoffPromotionSlots = entity.getPlayoffPromotionSlots();
        maxParticipants = entity.getMaxParticipants();
        sortOrder = entity.getSortOrder();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        DivisionResponse divisionResponse = new DivisionResponse( id, tournamentId, name, level, promotionSlots, relegationSlots, playoffPromotionSlots, maxParticipants, sortOrder, createdAt, updatedAt );

        return divisionResponse;
    }

    @Override
    public ParticipantResponse toParticipantResponse(TournamentParticipantEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long divisionId = null;
        Long teamId = null;
        Integer seed = null;
        String displayName = null;
        LocalDateTime joinedAt = null;

        id = entity.getId();
        divisionId = entity.getDivisionId();
        teamId = entity.getTeamId();
        seed = entity.getSeed();
        displayName = entity.getDisplayName();
        joinedAt = entity.getJoinedAt();

        String status = entity.getStatus().name();

        ParticipantResponse participantResponse = new ParticipantResponse( id, divisionId, teamId, seed, displayName, status, joinedAt );

        return participantResponse;
    }

    @Override
    public RosterResponse toRosterResponse(TournamentMatchRosterEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long matchId = null;
        Long participantId = null;
        Long userId = null;
        Boolean isStarter = null;
        Integer jerseyNumber = null;
        String position = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        matchId = entity.getMatchId();
        participantId = entity.getParticipantId();
        userId = entity.getUserId();
        isStarter = entity.getIsStarter();
        jerseyNumber = entity.getJerseyNumber();
        position = entity.getPosition();
        createdAt = entity.getCreatedAt();

        RosterResponse rosterResponse = new RosterResponse( id, matchId, participantId, userId, isStarter, jerseyNumber, position, createdAt );

        return rosterResponse;
    }

    @Override
    public PromotionRecordResponse toPromotionRecordResponse(TournamentPromotionRecordEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long tournamentId = null;
        Long teamId = null;
        Long fromDivisionId = null;
        Long toDivisionId = null;
        Integer finalRank = null;
        String reason = null;
        Long executedBy = null;
        LocalDateTime executedAt = null;

        id = entity.getId();
        tournamentId = entity.getTournamentId();
        teamId = entity.getTeamId();
        fromDivisionId = entity.getFromDivisionId();
        toDivisionId = entity.getToDivisionId();
        finalRank = entity.getFinalRank();
        reason = entity.getReason();
        executedBy = entity.getExecutedBy();
        executedAt = entity.getExecutedAt();

        String type = entity.getType().name();

        PromotionRecordResponse promotionRecordResponse = new PromotionRecordResponse( id, tournamentId, teamId, fromDivisionId, toDivisionId, type, finalRank, reason, executedBy, executedAt );

        return promotionRecordResponse;
    }
}
