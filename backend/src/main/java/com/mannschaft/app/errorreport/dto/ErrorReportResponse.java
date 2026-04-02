package com.mannschaft.app.errorreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * エラーレポート詳細レスポンス（管理者向け）。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErrorReportResponse {

    private Long id;
    private String errorMessage;
    private String stackTrace;
    private String pageUrl;
    private String userAgent;
    private String userComment;
    private Long userId;
    private Long organizationId;
    private String requestId;
    private String ipAddress;
    private LocalDateTime occurredAt;
    private String status;
    private String severity;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private String adminNote;
    private String latestUserComment;
    private String errorHash;
    private int occurrenceCount;
    private int affectedUserCount;
    private LocalDateTime firstOccurredAt;
    private LocalDateTime lastOccurredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
