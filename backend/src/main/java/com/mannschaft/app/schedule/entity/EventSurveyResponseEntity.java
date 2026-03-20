package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * イベントアンケート回答エンティティ。ユーザーごとのアンケート回答を管理する。
 */
@Entity
@Table(name = "event_survey_responses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EventSurveyResponseEntity extends BaseEntity {

    @Column(nullable = false)
    private Long eventSurveyId;

    @Column(nullable = false)
    private Long userId;

    @Column(columnDefinition = "TEXT")
    private String answerText;

    @Column(columnDefinition = "JSON")
    private String answerOptions;
}
