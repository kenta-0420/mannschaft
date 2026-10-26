package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * リマインド送信レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RemindResponse {

    private final int notifiedCount;
    private final String paymentItemName;
}
