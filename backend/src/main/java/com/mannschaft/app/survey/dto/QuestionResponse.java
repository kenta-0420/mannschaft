package com.mannschaft.app.survey.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 設問レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class QuestionResponse {

    Long id;
    Long surveyId;
    String questionType;
    QuestionContentDto content;
    QuestionScaleConfigDto scaleConfig;
    LocalDateTime createdAt;
    List<OptionResponse> options;

    public record QuestionContentDto(String questionText, Boolean isRequired, Integer displayOrder,
                                      Integer maxSelections) {}

    public record QuestionScaleConfigDto(Integer scaleMin, Integer scaleMax,
                                          String scaleMinLabel, String scaleMaxLabel) {}
}
