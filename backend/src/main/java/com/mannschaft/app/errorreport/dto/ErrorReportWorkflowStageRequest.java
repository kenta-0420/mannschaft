package com.mannschaft.app.errorreport.dto;

import com.mannschaft.app.errorreport.ErrorReportWorkflowStage;
import lombok.Getter;
import lombok.Setter;

/**
 * F12.5 Phase 2 — ワークフロー段階更新リクエスト。
 * {@code workflowStage = null} の場合「未着手」へリセットする。
 */
@Getter
@Setter
public class ErrorReportWorkflowStageRequest {

    private ErrorReportWorkflowStage workflowStage;
}
