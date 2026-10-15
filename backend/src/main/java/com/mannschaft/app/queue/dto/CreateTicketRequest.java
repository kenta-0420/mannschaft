package com.mannschaft.app.queue.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チケット発行リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTicketRequest {

    @Size(max = 50)
    private final String guestName;

    @Size(max = 20)
    private final String guestPhone;

    private final Short partySize;

    private final String source;

    @Size(max = 300)
    private final String note;
}
