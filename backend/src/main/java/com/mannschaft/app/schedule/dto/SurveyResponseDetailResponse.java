package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アンケート回答詳細レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SurveyResponseDetailResponse {

    private final Long surveyId;
    private final Long userId;
    private final String answerText;
    private final List<String> answerOptions;
}
