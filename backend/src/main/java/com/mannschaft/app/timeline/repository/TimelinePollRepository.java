package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelinePollEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * タイムライン投票リポジトリ。
 */
public interface TimelinePollRepository extends JpaRepository<TimelinePollEntity, Long> {

    /**
     * 投稿IDに紐付く投票を取得する。
     */
    Optional<TimelinePollEntity> findByTimelinePostId(Long timelinePostId);
}
