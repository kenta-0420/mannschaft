package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.SystemTournamentPresetStatDefEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * システムプリセット個人成績項目リポジトリ。
 */
public interface SystemTournamentPresetStatDefRepository extends JpaRepository<SystemTournamentPresetStatDefEntity, Long> {

    List<SystemTournamentPresetStatDefEntity> findByPresetIdOrderBySortOrderAsc(Long presetId);

    void deleteByPresetId(Long presetId);
}
