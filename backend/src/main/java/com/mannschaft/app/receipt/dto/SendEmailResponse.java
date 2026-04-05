package com.mannschaft.app.receipt.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メール送信結果レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SendEmailResponse {
    private final Long receiptId;
    private final String email;
    private final String status;
}
