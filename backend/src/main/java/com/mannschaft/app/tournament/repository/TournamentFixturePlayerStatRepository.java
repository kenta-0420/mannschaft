package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentFixturePlayerStatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 個人成績リポジトリ。
 */
public interface TournamentFixturePlayerStatRepository extends JpaRepository<TournamentFixturePlayerStatEntity, Long> {

    List<TournamentFixturePlayerStatEntity> findByMatchId(Long matchId);

    List<TournamentFixturePlayerStatEntity> findByMatchIdAndUserId(Long matchId, Long userId);

    Optional<TournamentFixturePlayerStatEntity> findByMatchIdAndUserIdAndStatKey(
            Long matchId, Long userId, String statKey);

    /**
     * 当該 fixture（matchId）の指定 statKey 群のスナップショット行を一括削除する（F08.10 05 §H.2.2）。
     *
     * <p>個人ランキングの基本スタッツ（得点/アシスト）を {@code match_events} 正本へ正本化する際、
     * 試合完了イベント受信ごとに「当該 fixture の基本 statKey 行を delete → match 集計から再 insert」
     * することで冪等（再 COMPLETED で二重計上しない・05 §H.2 (d)）にする。大会固有の独自 statKey
     * （H.6）は削除対象に含めないため、本メソッドは statKey を明示指定で受ける。</p>
     */
    @Modifying
    void deleteByMatchIdAndStatKeyIn(Long matchId, Collection<String> statKeys);

    @Query("SELECT ps FROM TournamentFixturePlayerStatEntity ps " +
           "JOIN TournamentFixtureEntity m ON ps.matchId = m.id " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "JOIN TournamentDivisionEntity d ON md.divisionId = d.id " +
           "WHERE d.tournamentId = :tournamentId AND ps.statKey = :statKey AND m.status = 'COMPLETED'")
    List<TournamentFixturePlayerStatEntity> findByTournamentIdAndStatKey(
            @Param("tournamentId") Long tournamentId, @Param("statKey") String statKey);

    @Query("SELECT ps FROM TournamentFixturePlayerStatEntity ps " +
           "JOIN TournamentFixtureEntity m ON ps.matchId = m.id " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "JOIN TournamentDivisionEntity d ON md.divisionId = d.id " +
           "WHERE d.tournamentId = :tournamentId AND m.status = 'COMPLETED'")
    List<TournamentFixturePlayerStatEntity> findByTournamentId(
            @Param("tournamentId") Long tournamentId);
}
