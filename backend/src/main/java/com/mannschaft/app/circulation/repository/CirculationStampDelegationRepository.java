package com.mannschaft.app.circulation.repository;

import com.mannschaft.app.circulation.entity.CirculationStampDelegationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 押印委任リポジトリ。
 */
public interface CirculationStampDelegationRepository
        extends JpaRepository<CirculationStampDelegationEntity, UUID> {

    /**
     * 文書IDと委任者IDで ACTIVE な委任を取得する。
     * UNIQUE 制約により同一 (document_id, delegator_user_id) に対し 1 件まで。
     */
    Optional<CirculationStampDelegationEntity> findByDocumentIdAndDelegatorUserIdAndStatus(
            Long documentId, Long delegatorUserId, CirculationStampDelegationEntity.Status status);

    /** 委任者・文書・全ステータスで取得（重複登録チェック用）。 */
    Optional<CirculationStampDelegationEntity> findByDocumentIdAndDelegatorUserId(
            Long documentId, Long delegatorUserId);

    /** 文書IDで委任一覧を取得。 */
    List<CirculationStampDelegationEntity> findByDocumentIdOrderByCreatedAtAsc(Long documentId);
}
