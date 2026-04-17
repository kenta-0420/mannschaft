package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleMediaUploadEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * スケジュールメディアアップロードリポジトリ。
 */
@Repository
public interface ScheduleMediaUploadRepository extends JpaRepository<ScheduleMediaUploadEntity, Long> {

    /**
     * スケジュール別メディア一覧（作成日時降順）。
     */
    Page<ScheduleMediaUploadEntity> findByScheduleIdOrderByCreatedAtDesc(Long scheduleId, Pageable pageable);

    /**
     * スケジュール別・メディア種別フィルタ（作成日時降順）。
     */
    Page<ScheduleMediaUploadEntity> findByScheduleIdAndMediaTypeOrderByCreatedAtDesc(
            Long scheduleId, String mediaType, Pageable pageable);

    /**
     * スケジュール別・経費証憑のみ（作成日時降順）。
     */
    Page<ScheduleMediaUploadEntity> findByScheduleIdAndIsExpenseReceiptTrueOrderByCreatedAtDesc(
            Long scheduleId, Pageable pageable);

    /**
     * スケジュール内のメディア数カウント（種別別）。
     */
    int countByScheduleIdAndMediaType(Long scheduleId, String mediaType);

    /**
     * カバー写真取得（schedule ごとに最大1件）。
     */
    List<ScheduleMediaUploadEntity> findByScheduleIdAndIsCoverTrue(Long scheduleId);

    /**
     * 孤立メディア取得（日次クリーンアップ用）。
     * schedule_id IS NULL かつ created_at が cutoff より古いレコードを返す。
     */
    @Query("SELECT e FROM ScheduleMediaUploadEntity e WHERE e.scheduleId IS NULL AND e.createdAt < :cutoff")
    List<ScheduleMediaUploadEntity> findOrphanMedia(@Param("cutoff") LocalDateTime cutoff);
}
