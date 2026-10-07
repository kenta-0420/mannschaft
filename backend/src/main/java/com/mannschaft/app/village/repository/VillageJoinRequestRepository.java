package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageJoinRequestEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
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
 * 村参加申請リポジトリ（APPROVAL 村のみ・F17.1 Phase 1）。
 */
public interface VillageJoinRequestRepository extends JpaRepository<VillageJoinRequestEntity, UUID> {

    /** 同一主体の PENDING 申請を取得（二重申請防止）。 */
    Optional<VillageJoinRequestEntity> findByVillageIdAndSubjectTypeAndSubjectIdAndStatus(
            UUID villageId, VillageSubjectType subjectType, Long subjectId, VillageRequestStatus status);

    /** 村の申請一覧（状態別）。 */
    Page<VillageJoinRequestEntity> findByVillageIdAndStatus(
            UUID villageId, VillageRequestStatus status, Pageable pageable);

    /**
     * 村内で「指定ユーザーが申請した」申請の履歴（新しい順）。
     *
     * <p>申請者向け EP（{@code GET /join-requests/me}）専用。
     * 絞り込みキー {@code requesterUserId} は取下げの認可条件
     * （{@code VillageJoinRequestService#withdraw}）と同一であり、
     * 「自分が出した申請」の定義を両者で一致させている。</p>
     *
     * <p><b>他人の行を読んでから弾くのではなく、そもそも読まない</b>ための絞り込みである。
     * 呼び出し側は必ず認証済みユーザー ID を渡すこと（クライアント指定値を渡してはならない）。</p>
     */
    List<VillageJoinRequestEntity> findByVillageIdAndRequesterUserIdOrderByCreatedAtDesc(
            UUID villageId, Long requesterUserId);

    /** 入村主体は不変。USER根を申請行より先にロックするため、managed entityを返さない。 */
    @Query("""
            SELECT r.subjectType AS subjectType, r.subjectId AS subjectId
            FROM VillageJoinRequestEntity r WHERE r.id = :requestId AND r.villageId = :villageId
            """)
    Optional<AdmissionSubject> findAdmissionSubject(
            @Param("villageId") UUID villageId, @Param("requestId") UUID requestId);

    interface AdmissionSubject {
        VillageSubjectType getSubjectType();
        Long getSubjectId();
    }

    /** 審査・取下げの更新を同一行で直列化する。呼出側でmanaged stateも再読込する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM VillageJoinRequestEntity r WHERE r.id = :requestId")
    Optional<VillageJoinRequestEntity> findByIdForUpdate(@Param("requestId") UUID requestId);
}
