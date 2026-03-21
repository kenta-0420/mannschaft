package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * タイムラインブックマークレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BookmarkResponse {

    private final Long id;
    private final Long userId;
    private final Long timelinePostId;
    private final LocalDateTime createdAt;
}
