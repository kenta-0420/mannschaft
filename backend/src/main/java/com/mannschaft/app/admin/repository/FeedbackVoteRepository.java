package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.entity.FeedbackVoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * フィードバック投票リポジトリ。
 */
public interface FeedbackVoteRepository extends JpaRepository<FeedbackVoteEntity, Long> {

    /**
     * フィードバックIDとユーザーIDで投票を取得する。
     */
    Optional<FeedbackVoteEntity> findByFeedbackIdAndUserId(Long feedbackId, Long userId);

    /**
     * フィードバックIDとユーザーIDで投票を削除する。
     */
    void deleteByFeedbackIdAndUserId(Long feedbackId, Long userId);

    /**
     * フィードバックIDの投票数を取得する。
     */
    long countByFeedbackId(Long feedbackId);

    /**
     * ユーザーが既に投票済みか確認する。
     */
    boolean existsByFeedbackIdAndUserId(Long feedbackId, Long userId);
}
