package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 募集レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MatchRequestResponse {

    private final Long id;
    private final TeamSummaryResponse team;
    private final String title;
    private final String description;
    private final String activityType;
    private final String activityDetail;
    private final String category;
    private final String visibility;
    private final String prefectureCode;
    private final String cityCode;
    private final String venueName;
    private final LocalDate preferredDateFrom;
    private final LocalDate preferredDateTo;
    private final LocalTime preferredTimeFrom;
    private final LocalTime preferredTimeTo;
    private final String level;
    private final Short minParticipants;
    private final Short maxParticipants;
    private final String status;
    private final Integer proposalCount;
    private final LocalDateTime expiresAt;
    private final Short cancelCount;
    private final LocalDateTime createdAt;
}
