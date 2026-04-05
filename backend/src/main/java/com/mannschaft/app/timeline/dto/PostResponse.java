package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * タイムライン投稿レスポンスDTO（一覧用）。
 */
@Getter
@RequiredArgsConstructor
public class PostResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long userId;
    private final Long socialProfileId;
    private final String postedAsType;
    private final Long postedAsId;
    private final Long parentId;
    private final String content;
    private final Long repostOfId;
    private final Integer repostCount;
    private final String status;
    private final LocalDateTime scheduledAt;
    private final Boolean isPinned;
    private final Integer reactionCount;
    private final Integer replyCount;
    private final Short attachmentCount;
    private final Short editCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
