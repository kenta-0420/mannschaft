package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 議案コメントレスポンスDTO。
 */
@Getter
@Builder
public class CommentResponse {

    private final Long id;
    private final Long userId;
    private final String body;
    private final LocalDateTime createdAt;
}
