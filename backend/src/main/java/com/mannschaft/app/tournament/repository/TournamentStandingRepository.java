package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.dto.DivisionLeaderProjection;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 順位表リポジトリ。
 */
public interface TournamentStandingRepository extends JpaRepository<TournamentStandingEntity, Long> {

    List<TournamentStandingEntity> findByDivisionIdOrderByRankAsc(Long divisionId);

    Optional<TournamentStandingEntity> findByDivisionIdAndParticipantId(Long divisionId, Long participantId);

    void deleteByDivisionId(Long divisionId);

    /**
     * F08.7.1 主催大会サマリ: 複数ディビジョンの首位（rank=1）チームを 1 クエリで一括取得する（N+1 回避）。
     *
     * <p>{@code tournament_standings.rank = 1} の行と {@code tournament_participants} を JOIN し、
     * 首位チームの team_id / displayName を射影する。順位未計算（standing 不在）のディビジョンは
     * 結果に含まれない（呼び出し側で null フォールバック）。</p>
     *
     * @param divisionIds ディビジョン ID 集合（空の場合は空 List）
     * @return ディビジョン別首位チームの射影リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.tournament.dto.DivisionLeaderProjection(
                s.divisionId, p.teamId, p.displayName)
            FROM TournamentStandingEntity s
            JOIN TournamentParticipantEntity p ON p.id = s.participantId
            WHERE s.divisionId IN :divisionIds AND s.rank = 1
            """)
    List<DivisionLeaderProjection> findLeadersByDivisionIdIn(
            @Param("divisionIds") Collection<Long> divisionIds);
}
