package com.mannschaft.app.survey.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アンケート回答送信リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SubmitResponseRequest {

    @NotEmpty
    @Valid
    private final List<AnswerEntry> answers;

    /**
     * 各設問への回答エントリ。
     */
    @Getter
    @RequiredArgsConstructor
    public static class AnswerEntry {

        private final Long questionId;

        private final List<Long> optionIds;

        private final String textResponse;
    }
}
