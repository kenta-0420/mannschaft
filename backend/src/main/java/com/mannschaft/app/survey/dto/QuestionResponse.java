package com.mannschaft.app.survey.dto;

import com.mannschaft.app.survey.QuestionType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 設問レスポンスDTO。
 *
 * <p>{@code questionType} は enum 型のまま公開する（#2617-1）。JSON 表現は enum 名で不変。</p>
 */
@Builder(toBuilder = true)
@Getter
public class QuestionResponse {

    Long id;
    Long surveyId;
    QuestionType questionType;
    QuestionContentDto content;
    QuestionScaleConfigDto scaleConfig;
    LocalDateTime createdAt;
    List<OptionResponse> options;

    public record QuestionContentDto(String questionText, Boolean isRequired, Integer displayOrder,
                                      Integer maxSelections) {}

    public record QuestionScaleConfigDto(Integer scaleMin, Integer scaleMax,
                                          String scaleMinLabel, String scaleMaxLabel) {}
}
