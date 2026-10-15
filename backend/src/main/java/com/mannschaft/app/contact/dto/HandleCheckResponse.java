package com.mannschaft.app.contact.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * ハンドル重複確認レスポンス。
 */
@Getter
@AllArgsConstructor
public class HandleCheckResponse {
    private boolean available;
}
