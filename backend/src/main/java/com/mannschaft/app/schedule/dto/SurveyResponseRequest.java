package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アンケート回答リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SurveyResponseRequest {

    @NotNull
    private final Long surveyId;

    private final String answerText;

    private final List<String> answerOptions;
}
