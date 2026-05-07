package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.AuditLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 監査ログリポジトリ。
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /**
     * 指定日時より前に作成された監査ログをページング取得する（アーカイブバッチ用）。
     *
     * @param threshold 基準日時（この日時より前のログが対象）
     * @param pageable  ページング情報
     * @return スライス形式の監査ログ一覧
     */
    @Query("SELECT a FROM AuditLogEntity a WHERE a.createdAt < :threshold ORDER BY a.id ASC")
    Slice<AuditLogEntity> findOlderThan(@Param("threshold") LocalDateTime threshold, Pageable pageable);

    /**
     * 指定 ID より前の監査ログを物理削除する（アーカイブ完了後のクリーンアップ用）。
     *
     * @param maxId    削除対象の最大 ID（この ID 以下のレコードを削除）
     * @param threshold 基準日時（この日時より前かつ maxId 以下のレコードを削除。二重チェック）
     * @return 削除件数
     */
    @Modifying
    @Query("DELETE FROM AuditLogEntity a WHERE a.id <= :maxId AND a.createdAt < :threshold")
    int deleteArchivedLogs(@Param("maxId") Long maxId, @Param("threshold") LocalDateTime threshold);
}
