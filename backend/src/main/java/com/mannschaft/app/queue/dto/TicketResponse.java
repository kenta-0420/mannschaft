package com.mannschaft.app.queue.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * チケットレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TicketResponse {

    private final Long id;
    private final Long categoryId;
    private final Long counterId;
    private final String ticketNumber;
    private final Long userId;
    private final String guestName;
    private final Short partySize;
    private final String source;
    private final String status;
    private final Integer position;
    private final Short estimatedWaitMinutes;
    private final LocalDateTime calledAt;
    private final LocalDateTime servingAt;
    private final LocalDateTime completedAt;
    private final LocalDateTime cancelledAt;
    private final LocalDateTime noShowAt;
    private final LocalDateTime holdUntil;
    private final Boolean holdUsed;
    private final Short actualServiceMinutes;
    private final String note;
    private final Long previousTicketId;
    private final LocalDate issuedDate;
    private final LocalDateTime createdAt;
}
