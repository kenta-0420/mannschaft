package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * お知らせレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AnnouncementResponse {

    private final Long id;
    private final String title;
    private final String body;
    private final String priority;
    private final String targetScope;
    private final Boolean isPinned;
    private final LocalDateTime publishedAt;
    private final LocalDateTime expiresAt;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
