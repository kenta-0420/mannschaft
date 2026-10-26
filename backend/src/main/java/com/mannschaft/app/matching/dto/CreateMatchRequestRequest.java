package com.mannschaft.app.matching.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 募集作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateMatchRequestRequest {

    @NotBlank
    @Size(max = 100)
    private final String title;

    private final String description;

    @NotNull
    private final String activityType;

    @Size(max = 50)
    private final String activityDetail;

    private final String category;

    @NotBlank
    @Size(max = 2)
    private final String prefectureCode;

    @Size(max = 5)
    private final String cityCode;

    @Size(max = 200)
    private final String venueName;

    private final LocalDate preferredDateFrom;

    private final LocalDate preferredDateTo;

    private final LocalTime preferredTimeFrom;

    private final LocalTime preferredTimeTo;

    private final String level;

    private final Short minParticipants;

    private final Short maxParticipants;

    private final String visibility;

    private final LocalDateTime expiresAt;
}
