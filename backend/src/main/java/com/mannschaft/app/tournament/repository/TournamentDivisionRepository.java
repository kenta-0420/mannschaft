package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ディビジョンリポジトリ。
 */
public interface TournamentDivisionRepository extends JpaRepository<TournamentDivisionEntity, Long> {

    List<TournamentDivisionEntity> findByTournamentIdOrderByLevelAscSortOrderAsc(Long tournamentId);

    Optional<TournamentDivisionEntity> findByIdAndTournamentId(Long id, Long tournamentId);

    /**
     * F08.7.1 主催大会サマリ: 複数大会のディビジョンを 1 クエリで一括取得する（N+1 回避）。
     *
     * @param tournamentIds 大会 ID 集合（空の場合は空 List）
     * @return 対象ディビジョン一覧（level / sortOrder 昇順）
     */
    List<TournamentDivisionEntity> findByTournamentIdInOrderByLevelAscSortOrderAsc(
            @Param("tournamentIds") Collection<Long> tournamentIds);
}
