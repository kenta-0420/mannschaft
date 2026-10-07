package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageCreationRequestEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村作成申請リポジトリ（F17.1 Phase 1）。
 */
public interface VillageCreationRequestRepository extends JpaRepository<VillageCreationRequestEntity, UUID> {

    Page<VillageCreationRequestEntity> findByStatus(VillageRequestStatus status, Pageable pageable);

    List<VillageCreationRequestEntity> findByRequesterUserIdOrderByCreatedAtDesc(Long requesterUserId);

    /** 申請レートリミット用: 指定ユーザーの直近申請数。 */
    long countByRequesterUserIdAndCreatedAtAfter(Long requesterUserId, java.time.LocalDateTime since);

    /** 不変の申請者IDだけを読み、申請行より先に本人のUSER根を取得する。 */
    @Query("SELECT r.requesterUserId FROM VillageCreationRequestEntity r WHERE r.id = :requestId")
    Optional<Long> findAdmissionRequesterId(@Param("requestId") UUID requestId);

    /** 審査・取下げの更新を同一行で直列化する。呼出側でmanaged stateも再読込する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM VillageCreationRequestEntity r WHERE r.id = :requestId")
    Optional<VillageCreationRequestEntity> findByIdForUpdate(@Param("requestId") UUID requestId);
}
