package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import org.springframework.data.domain.Page;
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
     * スコープ別・ステータス別に通報一覧をページング取得する。
     */
    Page<ContentReportEntity> findByScopeTypeAndScopeIdAndStatus(
            String scopeType, Long scopeId, ReportStatus status, Pageable pageable);

    /**
     * スコープ別の通報一覧をページング取得する。
     */
    Page<ContentReportEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId, Pageable pageable);

    /**
     * 対象別に通報一覧を取得する。
     */
    List<ContentReportEntity> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            ReportTargetType targetType, Long targetId);

    /**
     * 同一通報者・同一対象の重複チェックを行う。
     */
    boolean existsByReportedByAndTargetTypeAndTargetId(
            Long reportedBy, ReportTargetType targetType, Long targetId);

    /**
     * ステータス別の通報数を取得する。
     */
    long countByStatus(ReportStatus status);

    /**
     * 対象ユーザーの通報件数を取得する。
     */
    long countByTargetUserIdAndStatus(Long targetUserId, ReportStatus status);

    /**
     * 同一コンテンツの全通報を取得する（グルーピング解決用）。
     */
    List<ContentReportEntity> findByTargetTypeAndTargetId(ReportTargetType targetType, Long targetId);
}
