package com.mannschaft.app.common.storage.quota.repository;

import com.mannschaft.app.common.storage.quota.entity.StorageSubscriptionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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
     * 同一 scope_type の複数 scope_id をまとめて取得する（使用量参照の一括取得・N+1 回避）。
     *
     * <p>{@code GET /api/v1/me/storage/usage} が本人の所属スコープ群の subscription を一括取得するために使う。
     * 未作成スコープは結果に含まれない（呼び出し側で 0 埋めする）。{@code scopeIds} が空の場合は呼び出し側で
     * 本メソッドを呼ばずに空リスト扱いとすること（空 {@code IN} 句を避ける）。</p>
     */
    List<StorageSubscriptionEntity> findByScopeTypeAndScopeIdIn(String scopeType, Collection<Long> scopeIds);

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
