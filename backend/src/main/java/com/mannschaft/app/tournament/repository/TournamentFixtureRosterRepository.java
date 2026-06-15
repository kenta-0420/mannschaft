package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentFixtureRosterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;

/**
 * 出場メンバーリポジトリ。
 */
public interface TournamentFixtureRosterRepository extends JpaRepository<TournamentFixtureRosterEntity, Long> {

    List<TournamentFixtureRosterEntity> findByMatchIdOrderByParticipantIdAscJerseyNumberAsc(Long matchId);

    List<TournamentFixtureRosterEntity> findByMatchIdAndParticipantId(Long matchId, Long participantId);

    /** 自チーム分の roster を sortOrder 相当（背番号→id）で取得する（rosters/me 取得用） */
    List<TournamentFixtureRosterEntity> findByMatchIdAndParticipantIdOrderByJerseyNumberAscIdAsc(
            Long matchId, Long participantId);

    void deleteByMatchId(Long matchId);

    /** 自チーム分の roster を全削除する（PUT rosters/me・apply-template の全置換 UPSERT に使用） */
    @Modifying
    void deleteByMatchIdAndParticipantId(Long matchId, Long participantId);
}
