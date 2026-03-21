package com.mannschaft.app.ticket.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チケット消化リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ConsumeTicketRequest {

    private final Long reservationId;

    private final Long serviceRecordId;

    private final LocalDateTime consumedAt;

    @Size(max = 500)
    private final String note;
}
