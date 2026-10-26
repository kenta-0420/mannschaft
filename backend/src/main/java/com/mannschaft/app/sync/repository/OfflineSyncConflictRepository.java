package com.mannschaft.app.sync.repository;

import com.mannschaft.app.sync.entity.OfflineSyncConflictEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F11.1 オフライン同期: コンフリクトリポジトリ。
 * 未解決コンフリクトの一覧取得、詳細取得、清掃バッチ用の削除、UPSERT 用の検索を提供する。
 */
public interface OfflineSyncConflictRepository extends JpaRepository<OfflineSyncConflictEntity, Long> {

    /**
     * ユーザーの未解決コンフリクト一覧を取得する（作成日時の降順）。
     */
    Page<OfflineSyncConflictEntity> findByUserIdAndResolutionIsNullOrderByCreatedAtDesc(
            Long userId, Pageable pageable);

    /**
     * ID とユーザーID でコンフリクトを取得する（権限チェック兼用）。
     */
    Optional<OfflineSyncConflictEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 指定日時より前に解決されたコンフリクトを削除する（90日清掃バッチ用）。
     */
    long deleteByResolvedAtBeforeAndResolutionIsNotNull(LocalDateTime cutoff);

    /**
     * ユーザー・リソース種別・リソースID で未解決のコンフリクトを検索する（UPSERT 用）。
     */
    List<OfflineSyncConflictEntity> findByUserIdAndResourceTypeAndResourceIdAndResolutionIsNull(
            Long userId, String resourceType, Long resourceId);
}
