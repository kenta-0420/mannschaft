package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Google Calendar OAuth連携リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class GoogleCalendarConnectRequest {

    @NotBlank
    private final String code;

    @NotBlank
    private final String state;

    @NotBlank
    private final String redirectUri;
}
