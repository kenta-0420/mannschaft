package com.mannschaft.app.errorreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

    // ===== F12.5 Phase 2 追加 =====

    /** ワークフロー段階（NULL は未着手）。 */
    private String workflowStage;

    // ===== F10.6 Phase 10-δ 追加 =====

    /** SLA 対応期限。severity=LOW は NULL。 */
    private LocalDateTime slaDueAt;

    /** 担当管理者ユーザーID。 */
    private Long assigneeId;

    /** 担当管理者の表示名（解決済み）。 */
    private String assigneeName;

    /** 連携した GitHub Issue の URL。 */
    private String githubIssueUrl;

    /** 最終 AI 分析実行日時。 */
    private LocalDateTime lastAiAnalysisAt;

    /** 最新 SUCCESS の AI 分析サマリー。 */
    @Setter
    private ErrorReportAiAnalysisSummary latestAiAnalysis;

    /**
     * 最新 SUCCESS AI 分析の表示用サマリー。P2-C で実装。
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorReportAiAnalysisSummary {
        private Long id;
        private String estimatedCause;
        private String fixProposal;
        private String impactAssessment;
        private String suggestedFiles;
        private LocalDateTime createdAt;
    }
}
