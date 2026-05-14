package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageLobbyDailyThreadEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 井戸端会議日次スレッドリポジトリ（F17.1 Phase 1）。
 */
public interface VillageLobbyDailyThreadRepository extends JpaRepository<VillageLobbyDailyThreadEntity, UUID> {

    Optional<VillageLobbyDailyThreadEntity> findByVillageIdAndThreadDate(UUID villageId, LocalDate threadDate);
}
