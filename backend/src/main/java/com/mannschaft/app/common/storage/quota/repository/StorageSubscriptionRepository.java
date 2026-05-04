package com.mannschaft.app.common.storage.quota.repository;

import com.mannschaft.app.common.storage.quota.entity.StorageSubscriptionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * F13 ストレージサブスクリプションのリポジトリ。
 *
 * <p>使用量更新時は {@link #findForUpdate} で悲観ロックを取り、複数同時アップロード時の
 * lost update を防止する。</p>
 */
public interface StorageSubscriptionRepository extends JpaRepository<StorageSubscriptionEntity, Long> {

    /**
     * スコープで検索（読み取りのみ）。
     */
    Optional<StorageSubscriptionEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * スコープで検索（悲観ロック取得。{@code recordUpload} / {@code recordDeletion} 用）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StorageSubscriptionEntity s "
            + "WHERE s.scopeType = :scopeType AND s.scopeId = :scopeId")
    Optional<StorageSubscriptionEntity> findForUpdate(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);
}
