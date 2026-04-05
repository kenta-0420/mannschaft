package com.mannschaft.app.auth.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 汎用メッセージレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class MessageResponse {

    private final String message;

    /**
     * ファクトリメソッド。
     */
    public static MessageResponse of(String message) {
        return new MessageResponse(message);
    }
}
