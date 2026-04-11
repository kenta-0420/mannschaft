package com.mannschaft.app.quickmemo.repository;

import com.mannschaft.app.quickmemo.entity.PendingUploadEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Presigned URL 発行履歴リポジトリ。
 */
public interface PendingUploadRepository extends JpaRepository<PendingUploadEntity, Long> {

    /**
     * メモの未確認・有効期限内のPending URLを取得する（1メモ1URL制限チェック用）。
     */
    @Query("""
            SELECT p FROM PendingUploadEntity p
            WHERE p.memoId = :memoId
              AND p.confirmedAt IS NULL
              AND p.presignedUrlExpiresAt > :now
            """)
    Optional<PendingUploadEntity> findActivePendingByMemoId(
            @Param("memoId") Long memoId, @Param("now") LocalDateTime now);

    /**
     * ユーザーの指定時刻以降に発行された Pending URL の宣言容量合計を取得する（1時間100MB制限用）。
     */
    @Query("""
            SELECT COALESCE(SUM(p.declaredSizeBytes), 0)
            FROM PendingUploadEntity p
            WHERE p.userId = :userId AND p.createdAt >= :since
            """)
    Long sumDeclaredSizeSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * S3キーで Pending Upload を取得する。
     */
    Optional<PendingUploadEntity> findByS3Key(String s3Key);

    /**
     * 孤立した（未確認・期限切れ）Pending Upload を取得する（クリーンアップバッチ用）。
     */
    @Query("""
            SELECT p FROM PendingUploadEntity p
            WHERE p.confirmedAt IS NULL AND p.presignedUrlExpiresAt < :now
            """)
    List<PendingUploadEntity> findExpiredPendingUploads(@Param("now") LocalDateTime now);

    /**
     * S3キーで削除する。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM PendingUploadEntity p WHERE p.s3Key = :s3Key")
    void deleteByS3Key(@Param("s3Key") String s3Key);
}
