package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.FeedbackStatus;
import com.mannschaft.app.admin.entity.FeedbackSubmissionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * フィードバック投稿リポジトリ。
 */
public interface FeedbackSubmissionRepository extends JpaRepository<FeedbackSubmissionEntity, Long> {

    /**
     * スコープとステータスでフィードバック一覧を取得する。
     */
    Page<FeedbackSubmissionEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            String scopeType, Long scopeId, FeedbackStatus status, Pageable pageable);

    /**
     * スコープでフィードバック一覧を取得する。
     */
    Page<FeedbackSubmissionEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * 投稿者でフィードバック一覧を取得する。
     */
    Page<FeedbackSubmissionEntity> findBySubmittedByOrderByCreatedAtDesc(
            Long submittedBy, Pageable pageable);

    /**
     * プラットフォームスコープ（scopeId IS NULL）のステータス別件数を取得する。
     */
    @Query("SELECT COUNT(f) FROM FeedbackSubmissionEntity f WHERE f.scopeType = :scopeType AND f.scopeId IS NULL AND f.status = :status")
    long countByScopeTypeAndScopeIdIsNullAndStatus(String scopeType, FeedbackStatus status);

    /**
     * ステータス別の件数を取得する。
     */
    long countByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, FeedbackStatus status);
}
