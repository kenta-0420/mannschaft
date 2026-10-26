package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageCreationRequestEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 村作成申請リポジトリ（F17.1 Phase 1）。
 */
public interface VillageCreationRequestRepository extends JpaRepository<VillageCreationRequestEntity, UUID> {

    Page<VillageCreationRequestEntity> findByStatus(VillageRequestStatus status, Pageable pageable);

    List<VillageCreationRequestEntity> findByRequesterUserIdOrderByCreatedAtDesc(Long requesterUserId);

    /** 申請レートリミット用: 指定ユーザーの直近申請数。 */
    long countByRequesterUserIdAndCreatedAtAfter(Long requesterUserId, java.time.LocalDateTime since);
}
