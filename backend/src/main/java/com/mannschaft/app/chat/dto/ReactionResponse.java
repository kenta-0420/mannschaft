package com.mannschaft.app.chat.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * リアクションレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReactionResponse {

    private final Long id;
    private final Long messageId;
    private final Long userId;
    private final String emoji;
    private final LocalDateTime createdAt;
}
