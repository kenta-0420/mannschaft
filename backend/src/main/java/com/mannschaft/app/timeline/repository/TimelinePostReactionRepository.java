package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelinePostReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * タイムラインリアクションリポジトリ。
 */
public interface TimelinePostReactionRepository extends JpaRepository<TimelinePostReactionEntity, Long> {

    /**
     * 投稿に対するリアクション一覧を取得する。
     */
    List<TimelinePostReactionEntity> findByTimelinePostId(Long timelinePostId);

    /**
     * 投稿・ユーザー・絵文字でリアクションを取得する。
     */
    Optional<TimelinePostReactionEntity> findByTimelinePostIdAndUserIdAndEmoji(
            Long timelinePostId, Long userId, String emoji);

    /**
     * 投稿ごとの絵文字別リアクション集計を取得する。
     */
    @Query("SELECT r.emoji, COUNT(r) FROM TimelinePostReactionEntity r "
            + "WHERE r.timelinePostId = :postId GROUP BY r.emoji ORDER BY COUNT(r) DESC")
    List<Object[]> countByPostIdGroupByEmoji(@Param("postId") Long postId);

    /**
     * ユーザーが投稿にリアクション済みかを判定する。
     */
    boolean existsByTimelinePostIdAndUserId(Long timelinePostId, Long userId);
}
