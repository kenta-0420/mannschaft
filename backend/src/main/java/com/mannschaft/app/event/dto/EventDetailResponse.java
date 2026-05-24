package com.mannschaft.app.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * イベント詳細レスポンスDTO。
 * トップレベル8フィールド＋サブレコード構成でネスト設計を採用。
 */
@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventDetailResponse {

    private Long id;
    private EventScopeDto scope;
    private EventContentDto content;
    private EventVenueDto venue;
    private EventRegistrationDto registration;
    private EventMetaDto meta;
    /** attendance_mode=RSVP 時のみ非null。withRsvpSummary() で後から設定する */
    private EventRsvpSummaryResponse rsvpSummary;
    private EventAuditDto audit;

    public record EventScopeDto(
            String scopeType,
            Long scopeId,
            Long scheduleId,
            Long workflowRequestId
    ) {}

    public record EventContentDto(
            String slug,
            String subtitle,
            String summary,
            String coverImageKey
    ) {}

    public record EventVenueDto(
            String venueName,
            String venueAddress,
            BigDecimal venueLatitude,
            BigDecimal venueLongitude,
            String venueAccessInfo
    ) {}

    public record EventRegistrationDto(
            LocalDateTime registrationStartsAt,
            LocalDateTime registrationEndsAt,
            Integer maxCapacity,
            Boolean isApprovalRequired,
            EventAttendanceMode attendanceMode,
            Long preSurveyId,
            Long postSurveyId,
            Integer registrationCount,
            Integer checkinCount
    ) {}

    public record EventMetaDto(
            String status,
            String visibility,
            String ogpTitle,
            String ogpDescription,
            String ogpImageKey
    ) {}

    public record EventAuditDto(
            Long createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Long version
    ) {}

    /**
     * rsvpSummary は mapper 後に設定するため toBuilder() を使うファクトリメソッドを提供。
     *
     * @param rsvpSummary 設定するRSVPサマリー
     * @return rsvpSummary がセットされた新しいインスタンス
     */
    public EventDetailResponse withRsvpSummary(EventRsvpSummaryResponse rsvpSummary) {
        return this.toBuilder().rsvpSummary(rsvpSummary).build();
    }
}
