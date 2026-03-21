package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * コンテンツ通報リポジトリ。
 */
public interface ContentReportRepository extends JpaRepository<ContentReportEntity, Long> {

    /**
     * ステータス別に通報一覧を取得する。
     */
    List<ContentReportEntity> findByStatusOrderByCreatedAtAsc(ReportStatus status, Pageable pageable);

    /**
     * 対象別に通報一覧を取得する。
     */
    List<ContentReportEntity> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            ReportTargetType targetType, Long targetId);

    /**
     * 同一通報者・同一対象の重複チェックを行う。
     */
    boolean existsByReporterTypeAndReporterIdAndTargetTypeAndTargetId(
            String reporterType, Long reporterId, ReportTargetType targetType, Long targetId);

    /**
     * ステータス別の通報数を取得する。
     */
    long countByStatus(ReportStatus status);
}
