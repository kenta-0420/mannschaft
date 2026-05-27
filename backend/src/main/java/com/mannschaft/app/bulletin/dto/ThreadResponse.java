package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * スレッドレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ThreadResponse {

    private final Long id;
    private final Long categoryId;
    private final String scopeType;
    private final Long scopeId;
    private final Long authorId;
    private final String title;
    private final String body;
    private final String priority;
    private final String readTrackingMode;
    private final Boolean isPinned;
    private final Boolean isLocked;
    private final Boolean isArchived;
    /** 保管庫フォルダ ID（NULL かつ isArchived=true = 保管庫直下・未分類）。 */
    private final UUID archiveFolderId;
    private final Integer replyCount;
    private final Integer readCount;
    private final LocalDateTime lastRepliedAt;
    private final String sourceType;
    private final Long sourceId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
