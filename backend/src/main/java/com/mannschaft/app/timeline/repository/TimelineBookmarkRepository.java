package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelineBookmarkEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * タイムラインブックマークリポジトリ。
 */
public interface TimelineBookmarkRepository extends JpaRepository<TimelineBookmarkEntity, Long> {

    /**
     * ユーザーのブックマーク一覧を新着順で取得する。
     */
    List<TimelineBookmarkEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * ユーザー・投稿IDでブックマークを取得する。
     */
    Optional<TimelineBookmarkEntity> findByUserIdAndTimelinePostId(Long userId, Long timelinePostId);

    /**
     * ユーザーが投稿をブックマーク済みかを判定する。
     */
    boolean existsByUserIdAndTimelinePostId(Long userId, Long timelinePostId);
}
