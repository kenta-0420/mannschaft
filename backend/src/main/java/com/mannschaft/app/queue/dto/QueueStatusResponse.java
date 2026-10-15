package com.mannschaft.app.queue.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * キューステータスレスポンスDTO。リアルタイムの待ち状況を返す。
 */
@Getter
@RequiredArgsConstructor
public class QueueStatusResponse {

    private final Long counterId;
    private final String counterName;
    private final Boolean isAccepting;
    private final Integer waitingCount;
    private final Integer estimatedWaitMinutes;
    private final String currentTicketNumber;
    private final List<TicketResponse> waitingTickets;
}
