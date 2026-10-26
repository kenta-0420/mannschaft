package com.mannschaft.app.reservation.dto;

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
    private final Long reservationId;
    private final LocalDateTime remindAt;
    private final String status;
    private final LocalDateTime sentAt;
    private final LocalDateTime createdAt;
}
