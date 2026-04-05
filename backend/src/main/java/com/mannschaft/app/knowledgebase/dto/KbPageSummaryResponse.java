package com.mannschaft.app.knowledgebase.dto;

import com.mannschaft.app.knowledgebase.PageAccessLevel;
import com.mannschaft.app.knowledgebase.PageStatus;

import java.time.LocalDateTime;

/**
 * ナレッジベースページサマリーレスポンス（ツリー表示・一覧表示用）。
 */
public record KbPageSummaryResponse(
        Long id,
        Long parentId,
        String path,
        int depth,
        String title,
        String slug,
        String icon,
        PageAccessLevel accessLevel,
        PageStatus status,
        int viewCount,
        Long version,
        LocalDateTime updatedAt
) {}
