package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 手動発行結果レスポンスDTO。チケットと決済情報を含む。
 */
@Getter
@RequiredArgsConstructor
public class IssueResultResponse {

    private final TicketBookResponse ticketBook;
    private final PaymentResponse payment;

    /**
     * 決済レスポンス。
     */
    @Getter
    @RequiredArgsConstructor
    public static class PaymentResponse {
        private final Long id;
        private final String paymentMethod;
        private final Integer amount;
        private final String status;
    }
}
