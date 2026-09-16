package com.mannschaft.app.files.repository;

import com.mannschaft.app.files.entity.MultipartUploadSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Multipart Upload セッションの永続化リポジトリ。
 * セッションの検索・一覧取得をサポートする。
 */
public interface MultipartUploadSessionRepository extends JpaRepository<MultipartUploadSessionEntity, Long> {

    /**
     * R2 Upload ID でセッションを検索する。
     *
     * @param uploadId R2 Multipart Upload ID
     * @return 該当セッション（存在しない場合は空）
     */
    Optional<MultipartUploadSessionEntity> findByUploadId(String uploadId);

    /** 完了と中断の競合を直列化するため、対象セッションを行ロック付きで取得する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MultipartUploadSessionEntity s WHERE s.uploadId = :uploadId")
    Optional<MultipartUploadSessionEntity> findByUploadIdForUpdate(@Param("uploadId") String uploadId);

    /**
     * 指定ステータスかつ有効期限が指定日時以前のセッション一覧を取得する。
     * 期限切れセッションのクリーンアップバッチで使用する。
     *
     * @param status セッション状態（"IN_PROGRESS" 等）
     * @param now    現在日時（この日時以前に期限切れのものを取得）
     * @return 該当セッションのリスト
     */
    List<MultipartUploadSessionEntity> findByStatusAndExpiresAtBefore(String status, LocalDateTime now);
}
