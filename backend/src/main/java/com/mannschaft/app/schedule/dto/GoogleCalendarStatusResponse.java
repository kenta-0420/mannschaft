package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * Google Calendar連携状態レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class GoogleCalendarStatusResponse {

    private final boolean isConnected;
    private final String googleAccountEmail;
    private final String googleCalendarId;
    private final boolean isActive;
    private final boolean personalSyncEnabled;
    private final SyncErrorDetail lastSyncError;

    /**
     * 同期エラー詳細。
     */
    public record SyncErrorDetail(
            String type,
            String message,
            LocalDateTime occurredAt
    ) {}
}
