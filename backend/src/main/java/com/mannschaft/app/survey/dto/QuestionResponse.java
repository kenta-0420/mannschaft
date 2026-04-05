package com.mannschaft.app.survey.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 設問レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class QuestionResponse {

    private final Long id;
    private final Long surveyId;
    private final String questionType;
    private final String questionText;
    private final Boolean isRequired;
    private final Integer displayOrder;
    private final Integer maxSelections;
    private final Integer scaleMin;
    private final Integer scaleMax;
    private final String scaleMinLabel;
    private final String scaleMaxLabel;
    private final LocalDateTime createdAt;
    private final List<OptionResponse> options;
}
