package com.mannschaft.app.knowledgebase.dto;

import java.time.LocalDateTime;

/**
 * ナレッジベースページリビジョン詳細レスポンス。
 */
public record KbPageRevisionResponse(
        Long id,
        Long kbPageId,
        int revisionNumber,
        String title,
        String body,
        Long editorId,
        String changeSummary,
        LocalDateTime createdAt
) {}
