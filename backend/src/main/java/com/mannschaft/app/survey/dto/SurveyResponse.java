package com.mannschaft.app.survey.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * アンケートレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class SurveyResponse {

    Long id;
    String status;
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
                                   String resultsVisibility, String unrespondedVisibility) {}

    public record SurveyDistributionDto(String distributionMode, Boolean autoPostToTimeline,
                                         String seriesId, String remindBeforeHours, Integer manualRemindCount,
                                         Boolean includeSupporters) {}

    public record SurveyScheduleDto(LocalDateTime startsAt, LocalDateTime expiresAt,
                                     LocalDateTime publishedAt, LocalDateTime closedAt) {}

    public record SurveyStatsDto(Integer responseCount, Integer targetCount) {}

    public record SurveyAuditDto(Long version, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
