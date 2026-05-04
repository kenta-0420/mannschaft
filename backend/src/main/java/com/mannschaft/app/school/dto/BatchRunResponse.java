package com.mannschaft.app.school.dto;

/** バッチ実行結果レスポンス。 */
public record BatchRunResponse(
        String batchName,
        int processedCount,
        String executedAt
) {}
