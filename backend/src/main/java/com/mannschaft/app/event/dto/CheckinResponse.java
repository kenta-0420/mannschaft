package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チェックインレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CheckinResponse {

    private final Long id;
    private final Long eventId;
    private final Long ticketId;
    private final String checkinType;
    private final Long checkedInBy;
    private final LocalDateTime checkedInAt;
    private final String note;
    private final LocalDateTime createdAt;
}
