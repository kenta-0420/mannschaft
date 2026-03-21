package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.SystemTournamentPresetEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * システムプリセットリポジトリ。
 */
public interface SystemTournamentPresetRepository extends JpaRepository<SystemTournamentPresetEntity, Long> {

    Page<SystemTournamentPresetEntity> findAllByOrderBySortOrderAsc(Pageable pageable);

    List<SystemTournamentPresetEntity> findAllByOrderBySortOrderAsc();

    Optional<SystemTournamentPresetEntity> findByNameAndDeletedAtIsNull(String name);

    Page<SystemTournamentPresetEntity> findBySportCategoryOrderBySortOrderAsc(String sportCategory, Pageable pageable);
}
