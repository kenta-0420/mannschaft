package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentMatchSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * セット別スコアリポジトリ。
 */
public interface TournamentMatchSetRepository extends JpaRepository<TournamentMatchSetEntity, Long> {

    List<TournamentMatchSetEntity> findByMatchIdOrderBySetNumberAsc(Long matchId);

    void deleteByMatchId(Long matchId);
}
