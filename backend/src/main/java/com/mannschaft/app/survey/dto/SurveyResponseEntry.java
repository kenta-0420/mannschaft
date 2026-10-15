package com.mannschaft.app.survey.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回答エントリレスポンスDTO。個別回答データを表す。
 */
@Getter
@RequiredArgsConstructor
public class SurveyResponseEntry {

    private final Long id;
    private final Long surveyId;
    private final Long questionId;
    private final Long userId;
    private final Long optionId;
    private final String textResponse;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
