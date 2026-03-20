package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アンケート設問レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class EventSurveyResponse {

    private final Long id;
    private final String question;
    private final String questionType;
    private final List<String> options;
    private final Boolean isRequired;
    private final Integer sortOrder;
}
