package com.mannschaft.app.contact.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 連絡先申請送信レスポンス（常に同じ形式を返す）。
 */
@Getter
@AllArgsConstructor
public class SendContactRequestResponse {
    private Long requestId;
    private String status;
}
