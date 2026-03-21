package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Google Calendar OAuth連携レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class GoogleCalendarConnectResponse {

    private final String googleAccountEmail;
    private final String googleCalendarId;
    private final boolean isActive;
}
