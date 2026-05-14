package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageJoinRequestEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 村参加申請リポジトリ（APPROVAL 村のみ・F17.1 Phase 1）。
 */
public interface VillageJoinRequestRepository extends JpaRepository<VillageJoinRequestEntity, UUID> {

    /** 同一主体の PENDING 申請を取得（二重申請防止）。 */
    Optional<VillageJoinRequestEntity> findByVillageIdAndSubjectTypeAndSubjectIdAndStatus(
            UUID villageId, VillageSubjectType subjectType, Long subjectId, VillageRequestStatus status);

    /** 村の申請一覧（状態別）。 */
    Page<VillageJoinRequestEntity> findByVillageIdAndStatus(
            UUID villageId, VillageRequestStatus status, Pageable pageable);
}
