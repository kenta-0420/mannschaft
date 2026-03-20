package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.EventSurveyResponseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * イベントアンケート回答リポジトリ。
 */
public interface EventSurveyResponseRepository extends JpaRepository<EventSurveyResponseEntity, Long> {

    /**
     * アンケート設問IDとユーザーIDで回答を取得する。
     */
    Optional<EventSurveyResponseEntity> findByEventSurveyIdAndUserId(Long surveyId, Long userId);

    /**
     * アンケート設問IDで回答一覧を取得する。
     */
    List<EventSurveyResponseEntity> findByEventSurveyIdOrderByCreatedAtAsc(Long surveyId);

    /**
     * アンケート設問IDで回答を全削除する。
     */
    void deleteByEventSurveyId(Long surveyId);
}
