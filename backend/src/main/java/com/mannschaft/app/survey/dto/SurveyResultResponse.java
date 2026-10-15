package com.mannschaft.app.survey.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アンケート結果レスポンスDTO。集計結果を含む。
 */
@Getter
@RequiredArgsConstructor
public class SurveyResultResponse {

    private final Long surveyId;
    private final String title;
    private final Integer responseCount;
    private final Integer targetCount;
    private final List<QuestionResultResponse> questionResults;

    /**
     * 設問ごとの集計結果。
     */
    @Getter
    @RequiredArgsConstructor
    public static class QuestionResultResponse {
        private final Long questionId;
        private final String questionText;
        private final String questionType;
        private final List<OptionResultResponse> optionResults;
        private final List<String> textResponses;
    }

    /**
     * 選択肢ごとの集計結果。
     */
    @Getter
    @RequiredArgsConstructor
    public static class OptionResultResponse {
        private final Long optionId;
        private final String optionText;
        private final long count;
        private final double percentage;
    }
}
