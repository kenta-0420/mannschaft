package com.mannschaft.app.common.storage.migration;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F13 Phase 5-b ストレージパス移行エラーリポジトリ。
 */
public interface StorageMigrationErrorRepository extends JpaRepository<StorageMigrationErrorEntity, Long> {

    /**
     * 未解決エラー件数を返す。
     *
     * @return 未解決（resolved_at IS NULL）のエラー件数
     */
    long countByResolvedAtIsNull();

    /**
     * 未解決エラーを作成日時昇順でページング取得する。
     *
     * @param pageable ページング条件
     * @return 未解決エラーリスト
     */
    List<StorageMigrationErrorEntity> findByResolvedAtIsNullOrderByCreatedAtAsc(Pageable pageable);
}
