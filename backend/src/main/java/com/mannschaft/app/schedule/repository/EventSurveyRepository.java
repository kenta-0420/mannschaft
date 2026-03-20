package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.EventSurveyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * イベントアンケート設問リポジトリ。
 */
public interface EventSurveyRepository extends JpaRepository<EventSurveyEntity, Long> {

    /**
     * スケジュールIDでアンケート設問一覧を取得する。
     */
    List<EventSurveyEntity> findByScheduleIdOrderBySortOrderAsc(Long scheduleId);

    /**
     * スケジュールIDでアンケート設問数を取得する。
     */
    long countByScheduleId(Long scheduleId);

    /**
     * スケジュールIDでアンケート設問を全削除する。
     */
    void deleteByScheduleId(Long scheduleId);
}
