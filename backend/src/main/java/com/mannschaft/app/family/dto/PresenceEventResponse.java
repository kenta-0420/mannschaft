package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * プレゼンスイベントレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresenceEventResponse {

    private final Long id;
    private final String eventType;
    private final String message;
    private final String destination;
    private final LocalDateTime expectedReturnAt;
    private final UserSummary user;
    private final LocalDateTime createdAt;

    /**
     * ユーザーサマリー情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class UserSummary {
        private final Long id;
        private final String displayName;
    }
}
