package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 活動記録更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateActivityRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotNull
    private final LocalDate activityDate;

    private final LocalTime activityTimeStart;

    private final LocalTime activityTimeEnd;

    @Size(max = 10000)
    private final String description;

    private final Map<String, Object> fieldValues;

    private final String visibility;

    private final List<Long> participantUserIds;

    private final List<Long> fileIds;
}
