package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チケットレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TicketResponse {

    private final Long id;
    private final Long registrationId;
    private final Long eventId;
    private final Long ticketTypeId;
    private final String qrToken;
    private final String ticketNumber;
    private final String status;
    private final LocalDateTime usedAt;
    private final LocalDateTime cancelledAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
