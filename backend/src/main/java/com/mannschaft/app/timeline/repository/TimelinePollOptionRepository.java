package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelinePollOptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * タイムライン投票選択肢リポジトリ。
 */
public interface TimelinePollOptionRepository extends JpaRepository<TimelinePollOptionEntity, Long> {

    /**
     * 投票IDに紐付く選択肢を表示順で取得する。
     */
    List<TimelinePollOptionEntity> findByTimelinePollIdOrderBySortOrderAsc(Long timelinePollId);
}
