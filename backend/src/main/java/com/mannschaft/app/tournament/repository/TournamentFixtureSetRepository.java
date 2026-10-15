package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentFixtureSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * セット別スコアリポジトリ。
 */
public interface TournamentFixtureSetRepository extends JpaRepository<TournamentFixtureSetEntity, Long> {

    List<TournamentFixtureSetEntity> findByMatchIdOrderBySetNumberAsc(Long matchId);

    void deleteByMatchId(Long matchId);
}
