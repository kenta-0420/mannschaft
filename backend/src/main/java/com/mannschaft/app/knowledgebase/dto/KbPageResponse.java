package com.mannschaft.app.knowledgebase.dto;

import com.mannschaft.app.knowledgebase.PageAccessLevel;
import com.mannschaft.app.knowledgebase.PageStatus;

import java.time.LocalDateTime;

/**
 * ナレッジベースページ詳細レスポンス。
 */
public record KbPageResponse(
        Long id,
        String scopeType,
        Long scopeId,
        Long parentId,
        String path,
        int depth,
        String title,
        String slug,
        String body,
        String icon,
        PageAccessLevel accessLevel,
        PageStatus status,
        int viewCount,
        Long createdBy,
        Long lastEditedBy,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
