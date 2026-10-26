package com.mannschaft.app.errorreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F12.5 Phase 2-C — エラーレポート AI 分析履歴のレスポンス DTO。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorReportAiAnalysisResponse {

    private Long id;
    private Long errorReportId;
    private String modelName;
    private int promptTokens;
    private int completionTokens;
    private String estimatedCause;
    private String fixProposal;
    private String impactAssessment;

    /** {@code suggested_files} カラム（カンマ区切り）を split した配列。 */
    private List<String> suggestedFiles;

    private String status;
    private String errorMessage;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
}
