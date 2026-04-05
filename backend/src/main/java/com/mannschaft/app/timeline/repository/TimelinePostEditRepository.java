package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelinePostEditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * タイムライン投稿編集履歴リポジトリ。
 */
public interface TimelinePostEditRepository extends JpaRepository<TimelinePostEditEntity, Long> {

    /**
     * 投稿の編集履歴を時系列で取得する。
     */
    List<TimelinePostEditEntity> findByTimelinePostIdOrderByEditedAtDesc(Long timelinePostId);
}
