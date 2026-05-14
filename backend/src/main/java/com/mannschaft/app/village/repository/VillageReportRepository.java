package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageReportEntity;
import com.mannschaft.app.village.entity.enums.VillageReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村内通報リポジトリ（F17.1 Phase 1）。
 */
public interface VillageReportRepository extends JpaRepository<VillageReportEntity, UUID> {

    Page<VillageReportEntity> findByVillageIdAndStatus(
            UUID villageId, VillageReportStatus status, Pageable pageable);

    /** レートリミット用: 通報者ユーザーの直近通報数。 */
    long countByReporterUserIdAndCreatedAtAfter(Long reporterUserId, LocalDateTime since);
}
