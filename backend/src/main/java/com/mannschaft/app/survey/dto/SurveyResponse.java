package com.mannschaft.app.survey.dto;

import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.UnrespondedVisibility;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * アンケートレスポンスDTO。
 *
 * <p>enum 由来の項目は enum 型のまま公開する（#2617-1）。{@code String} で持つと
 * {@code docs/openapi.json} に許可値が出ず、FE との enum ドリフトを番人が検出できないため。
 * JSON 上の表現は enum 名そのままであり、従来の文字列値から変化しない。</p>
 */
@Builder(toBuilder = true)
@Getter
public class SurveyResponse {

    Long id;
    SurveyStatus status;
    SurveyScopeDto scope;
    SurveyContentDto content;
    SurveyPolicyDto policy;
    SurveyDistributionDto distribution;
    SurveyScheduleDto schedule;
    SurveyStatsDto stats;
    SurveyAuditDto audit;

    public record SurveyScopeDto(String scopeType, Long scopeId) {}

    public record SurveyContentDto(String title, String description) {}

    public record SurveyPolicyDto(Boolean isAnonymous, Boolean allowMultipleSubmissions,
                                   ResultsVisibility resultsVisibility,
                                   UnrespondedVisibility unrespondedVisibility) {}

    public record SurveyDistributionDto(DistributionMode distributionMode, Boolean autoPostToTimeline,
                                         String seriesId, String remindBeforeHours, Integer manualRemindCount,
                                         Boolean includeSupporters) {}

    public record SurveyScheduleDto(LocalDateTime startsAt, LocalDateTime expiresAt,
                                     LocalDateTime publishedAt, LocalDateTime closedAt) {}

    public record SurveyStatsDto(Integer responseCount, Integer targetCount) {}

    public record SurveyAuditDto(Long version, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
