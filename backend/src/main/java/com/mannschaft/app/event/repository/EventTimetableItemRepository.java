package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.entity.EventTimetableItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * イベントタイムテーブル項目リポジトリ。
 */
public interface EventTimetableItemRepository extends JpaRepository<EventTimetableItemEntity, Long> {

    /**
     * イベントのタイムテーブル一覧を取得する。
     */
    List<EventTimetableItemEntity> findByEventIdOrderBySortOrderAscStartAtAsc(Long eventId);

    /**
     * イベントのタイムテーブル項目数を取得する。
     */
    long countByEventId(Long eventId);
}
