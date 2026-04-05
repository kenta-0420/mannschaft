package com.mannschaft.app.filesharing.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * コメントレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CommentResponse {

    private final Long id;
    private final Long fileId;
    private final Long userId;
    private final String body;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
