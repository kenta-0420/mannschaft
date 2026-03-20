package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * リマインダーレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReminderResponse {

    private final Long id;
    private final LocalDateTime remindAt;
    private final Boolean isSent;
    private final LocalDateTime sentAt;
}
