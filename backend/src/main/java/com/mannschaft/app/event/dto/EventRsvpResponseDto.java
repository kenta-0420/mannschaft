package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * RSVP回答レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class EventRsvpResponseDto {

    private final Long id;
    private final Long eventId;
    private final Long userId;
    private final String userName;
    private final String response;
    private final String comment;
    private final LocalDateTime respondedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
