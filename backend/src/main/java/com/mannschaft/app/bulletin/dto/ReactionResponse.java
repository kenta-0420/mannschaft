package com.mannschaft.app.bulletin.dto;

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
    private final String targetType;
    private final Long targetId;
    private final Long userId;
    private final String emoji;
    private final LocalDateTime createdAt;
}
