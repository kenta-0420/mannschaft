package com.mannschaft.app.survey.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * アンケートシリーズ時系列比較レスポンス（F05.4 §4.9 /series/{seriesId}/comparison）。
 *
 * <p>同一 {@code series_id} のアンケートを時系列でグループ化し、共通設問の推移を返す。
 * 設問文・選択肢テキストの完全一致でマッチングする（設計書 §1287-1290）。</p>
 */
public record SurveyComparisonResponse(
        @JsonProperty("series_id") String seriesId,
        List<SurveySummary> surveys,
        @JsonProperty("question_comparisons") List<QuestionComparison> questionComparisons
) {

    /** シリーズ内の各アンケートのサマリ。 */
    public record SurveySummary(
            @JsonProperty("survey_id") Long surveyId,
            String title,
            @JsonProperty("published_at") LocalDateTime publishedAt,
            @JsonProperty("closed_at") LocalDateTime closedAt,
            @JsonProperty("response_count") Integer responseCount,
            @JsonProperty("target_count") Integer targetCount,
            @JsonProperty("response_rate") Double responseRate
    ) {
    }

    /** 設問単位の比較データ。 */
    public record QuestionComparison(
            @JsonProperty("question_text") String questionText,
            @JsonProperty("question_type") String questionType,
            List<QuestionTrend> trends
    ) {
    }

    /** アンケートごとの設問トレンド。 */
    public record QuestionTrend(
            @JsonProperty("survey_id") Long surveyId,
            List<OptionTrend> options,
            Double average
    ) {
    }

    /** 選択肢ごとのパーセンテージ。 */
    public record OptionTrend(
            @JsonProperty("option_text") String optionText,
            Double percentage
    ) {
    }
}
