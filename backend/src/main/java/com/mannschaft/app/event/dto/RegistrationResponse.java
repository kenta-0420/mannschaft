package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 参加登録レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RegistrationResponse {

    private final Long id;
    private final Long eventId;
    private final Long userId;
    private final Long ticketTypeId;
    private final String guestName;
    private final String guestEmail;
    private final String guestPhone;
    private final String status;
    private final Integer quantity;
    private final String note;
    private final Long approvedBy;
    private final LocalDateTime approvedAt;
    private final LocalDateTime cancelledAt;
    private final String cancelReason;
    private final Long inviteTokenId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
