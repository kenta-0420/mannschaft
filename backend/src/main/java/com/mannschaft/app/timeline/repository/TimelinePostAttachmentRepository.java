package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelinePostAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * タイムライン投稿添付ファイルリポジトリ。
 */
public interface TimelinePostAttachmentRepository extends JpaRepository<TimelinePostAttachmentEntity, Long> {

    /**
     * 投稿IDに紐付く添付ファイルを表示順で取得する。
     */
    List<TimelinePostAttachmentEntity> findByTimelinePostIdOrderBySortOrderAsc(Long timelinePostId);

    /**
     * 投稿IDに紐付く添付ファイルを削除する。
     */
    void deleteByTimelinePostId(Long timelinePostId);
}
