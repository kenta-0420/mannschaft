package com.mannschaft.app.survey.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 個別回答詳細レスポンス（F05.4 §4.8 /responses/{userId}）。
 *
 * <p>非匿名アンケート専用。匿名アンケート（{@code is_anonymous=true}）の場合、
 * Service 層で 403 を返す。</p>
 */
public record UserResponseDetailResponse(
        @JsonProperty("survey_id") Long surveyId,
        UserSummary user,
        @JsonProperty("responded_at") LocalDateTime respondedAt,
        List<UserResponseAnswerEntry> answers
) {

    /** 回答者の表示用サマリ。 */
    public record UserSummary(Long id, @JsonProperty("display_name") String displayName) {
    }
}
