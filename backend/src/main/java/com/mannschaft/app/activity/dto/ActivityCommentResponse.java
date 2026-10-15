package com.mannschaft.app.activity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 活動コメントレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ActivityCommentResponse {

    private final Long id;
    private final Long activityResultId;
    private final Long userId;
    private final String body;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
