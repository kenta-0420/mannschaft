package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.entity.FeedbackSubmissionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * フィードバック投稿リポジトリ。
 */
public interface FeedbackSubmissionRepository extends JpaRepository<FeedbackSubmissionEntity, Long> {

    /**
     * スコープとステータスでフィードバック一覧を取得する。
     */
    Page<FeedbackSubmissionEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            String scopeType, Long scopeId, String status, Pageable pageable);

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
     * ステータス別の件数を取得する。
     */
    long countByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, String status);
}
