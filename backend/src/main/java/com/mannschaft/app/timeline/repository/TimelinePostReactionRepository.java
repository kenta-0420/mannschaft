package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelinePostReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    /** 認可済み投稿群の実件数と閲覧者の反応を1クエリで集計する。反応0件の投稿は行を返さない。 */
    @Query("""
            SELECT r.timelinePostId AS postId, COUNT(r) AS reactionCount,
                   SUM(CASE WHEN r.userId = :userId THEN 1 ELSE 0 END) AS viewerReactionCount
            FROM TimelinePostReactionEntity r
            WHERE r.timelinePostId IN :postIds
            GROUP BY r.timelinePostId
            """)
    List<PostReactionSummary> summarizeByPostIds(
            @Param("postIds") Collection<Long> postIds, @Param("userId") Long userId);

    /** みたよ一覧用の集計結果。ユーザーID自体は外へ返さない。 */
    interface PostReactionSummary {
        Long getPostId();
        long getReactionCount();
        long getViewerReactionCount();
    }
}
