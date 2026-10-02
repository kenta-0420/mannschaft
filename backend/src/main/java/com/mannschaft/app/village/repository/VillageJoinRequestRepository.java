package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageJoinRequestEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
