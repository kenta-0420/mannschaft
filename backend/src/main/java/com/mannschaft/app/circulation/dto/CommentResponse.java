package com.mannschaft.app.circulation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回覧コメントレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CommentResponse {

    private final Long id;
    private final Long documentId;
    private final Long userId;
    private final String body;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
