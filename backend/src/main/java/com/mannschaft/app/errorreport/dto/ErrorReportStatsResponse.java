package com.mannschaft.app.errorreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * エラーレポート統計レスポンス。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErrorReportStatsResponse {

    private long totalNew;
    private long totalInvestigating;
    private long totalReopened;
    private long totalToday;
    private List<TopError> topErrors;

    /**
     * 頻出エラー情報。
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TopError {
        private String errorHash;
        private String errorMessage;
        private String pageUrl;
        private int occurrenceCount;
        private int affectedUserCount;
        private LocalDateTime lastOccurredAt;
    }
}
