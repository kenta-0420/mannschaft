package com.mannschaft.app.matching.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 募集レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class MatchRequestResponse {

    private final Long id;
    private final TeamSummaryResponse team;
    private final RequestContentDto content;
    private final RequestLocationDto location;
    private final RequestScheduleDto schedule;
    private final RequestParticipantsDto participants;
    private final RequestStatusDto status;
    private final LocalDateTime createdAt;

    public record RequestContentDto(
            String title,
            String description,
            String activityType,
            String activityDetail,
            String category,
            String visibility) {}

    public record RequestLocationDto(
            String prefectureCode,
            String cityCode,
            String venueName) {}

    public record RequestScheduleDto(
            LocalDate preferredDateFrom,
            LocalDate preferredDateTo,
            LocalTime preferredTimeFrom,
            LocalTime preferredTimeTo) {}

    public record RequestParticipantsDto(
            String level,
            Short minParticipants,
            Short maxParticipants) {}

    public record RequestStatusDto(
            String status,
            Integer proposalCount,
            LocalDateTime expiresAt,
            Short cancelCount) {}
}
