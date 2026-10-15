package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 出欠回答リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AttendanceRequest {

    @NotNull
    private final String status;

    @Size(max = 500)
    private final String comment;

    private final List<SurveyResponseRequest> surveyResponses;
}
