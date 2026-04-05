package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentMatchRosterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 出場メンバーリポジトリ。
 */
public interface TournamentMatchRosterRepository extends JpaRepository<TournamentMatchRosterEntity, Long> {

    List<TournamentMatchRosterEntity> findByMatchIdOrderByParticipantIdAscJerseyNumberAsc(Long matchId);

    List<TournamentMatchRosterEntity> findByMatchIdAndParticipantId(Long matchId, Long participantId);

    void deleteByMatchId(Long matchId);
}
