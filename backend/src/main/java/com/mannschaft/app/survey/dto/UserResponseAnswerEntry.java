package com.mannschaft.app.survey.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 個別回答の設問単位エントリ（F05.4 §4.8 /responses/{userId}）。
 *
 * <p>設問タイプにより使用されるフィールドが異なる:
 * <ul>
 *   <li>{@code SINGLE_CHOICE} / {@code MULTIPLE_CHOICE} → {@code optionIds} + {@code optionTexts}</li>
 *   <li>{@code FREE_TEXT} → {@code answerText}</li>
 *   <li>{@code SCALE} → {@code answerText}（スケール値を文字列で格納）</li>
 * </ul>
 */
public record UserResponseAnswerEntry(
        @JsonProperty("question_id") Long questionId,
        @JsonProperty("question_text") String questionText,
        @JsonProperty("question_type") String questionType,
        @JsonProperty("option_ids") List<Long> optionIds,
        @JsonProperty("option_texts") List<String> optionTexts,
        @JsonProperty("answer_text") String answerText
) {
}
