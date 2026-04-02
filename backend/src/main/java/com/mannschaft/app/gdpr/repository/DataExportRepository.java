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
 * GDPRデータエクスポートリポジトリ。
 */
public interface DataExportRepository extends JpaRepository<DataExportEntity, Long> {

    /**
     * ユーザーの最新エクスポートを取得する。
     */
    Optional<DataExportEntity> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 有効期限切れかつS3キーが存在するエクスポートを取得する。
     */
    List<DataExportEntity> findByExpiresAtBeforeAndS3KeyIsNotNull(LocalDateTime threshold);

    /**
     * スタックしたPROCESSINGをFAILEDにリセットする。
     */
    @Modifying
    @Query("UPDATE DataExportEntity e SET e.status = 'FAILED', e.errorMessage = :errorMessage " +
            "WHERE e.status = 'PROCESSING' AND e.updatedAt < :threshold")
    int resetStuckProcessing(@Param("threshold") LocalDateTime threshold,
                             @Param("errorMessage") String errorMessage);
}
