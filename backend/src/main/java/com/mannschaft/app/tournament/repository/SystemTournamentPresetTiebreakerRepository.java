package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.SystemTournamentPresetTiebreakerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * システムプリセットタイブレークリポジトリ。
 */
public interface SystemTournamentPresetTiebreakerRepository extends JpaRepository<SystemTournamentPresetTiebreakerEntity, Long> {

    List<SystemTournamentPresetTiebreakerEntity> findByPresetIdOrderByPriorityAsc(Long presetId);

    void deleteByPresetId(Long presetId);
}
