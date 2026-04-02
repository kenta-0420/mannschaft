package com.mannschaft.app.gdpr.repository;

import com.mannschaft.app.gdpr.entity.DataExportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * データエクスポートリポジトリ。
 */
public interface DataExportRepository extends JpaRepository<DataExportEntity, Long> {

    /**
     * 最新のエクスポートジョブを取得する。
     */
    Optional<DataExportEntity> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 有効期限切れかつS3キーありのレコードを取得する（期限切れファイルの削除用）。
     */
    List<DataExportEntity> findByExpiresAtBeforeAndS3KeyIsNotNull(LocalDateTime now);

    /**
     * スタックしたPROCESSINGジョブをFAILEDにリセットする。
     */
    @Modifying
    @Query("UPDATE DataExportEntity d SET d.status = 'FAILED', d.errorMessage = :message WHERE d.status = 'PROCESSING' AND d.createdAt < :threshold")
    int resetStuckProcessing(@Param("threshold") LocalDateTime threshold, @Param("message") String message);
}
