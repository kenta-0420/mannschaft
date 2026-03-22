package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.entity.FeedbackVoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * 複数フィードバックの投票数を一括取得する。
     */
    @Query("SELECT v.feedbackId, COUNT(v) FROM FeedbackVoteEntity v WHERE v.feedbackId IN :feedbackIds GROUP BY v.feedbackId")
    List<Object[]> countByFeedbackIds(@Param("feedbackIds") List<Long> feedbackIds);
}
