package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 返信レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReplyResponse {

    private final Long id;
    private final Long threadId;
    private final Long parentId;
    private final Long authorId;
    private final String body;
    private final Boolean isEdited;
    private final Integer replyCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<ReplyResponse> children;
}
