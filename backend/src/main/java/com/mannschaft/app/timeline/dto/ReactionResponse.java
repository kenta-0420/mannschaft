package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * タイムラインリアクションレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReactionResponse {

    private final Long id;
    private final Long timelinePostId;
    private final Long userId;
    private final String emoji;
    private final LocalDateTime createdAt;
}
