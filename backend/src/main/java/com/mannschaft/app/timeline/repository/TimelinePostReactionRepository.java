package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelinePostReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * タイムラインリアクション（みたよ！）リポジトリ。
 */
public interface TimelinePostReactionRepository extends JpaRepository<TimelinePostReactionEntity, Long> {

    /**
     * 投稿・ユーザーでリアクションを取得する。
     */
    Optional<TimelinePostReactionEntity> findByTimelinePostIdAndUserId(
            Long timelinePostId, Long userId);

    /**
     * ユーザーが投稿にリアクション済みかを判定する。
     */
    boolean existsByTimelinePostIdAndUserId(Long timelinePostId, Long userId);

    /**
     * 投稿のリアクション数を取得する。
     */
    long countByTimelinePostId(Long timelinePostId);
}
