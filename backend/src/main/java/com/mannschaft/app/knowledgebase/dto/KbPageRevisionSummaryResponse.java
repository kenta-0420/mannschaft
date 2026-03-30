package com.mannschaft.app.knowledgebase.dto;

import java.time.LocalDateTime;

/**
 * ナレッジベースページリビジョンサマリーレスポンス（一覧表示用）。
 */
public record KbPageRevisionSummaryResponse(
        Long id,
        int revisionNumber,
        Long editorId,
        String changeSummary,
        LocalDateTime createdAt
) {}
